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
 * Tracks one light sensor's activation across [SensorReporter] runs, and answers whether the panel
 * describes its illuminance channel.
 *
 * The answer turns on whether this install has EVER had a reading, not on the current run: a sensor
 * that reported once keeps its entity through any later refused registration, expired window or
 * silence, so an owner's configuration of a real sensor is never deleted by a slow boot. A sensor that
 * has never reported is never described, so nothing an owner could configure exists to be lost; it is
 * stated absent once that is settled (no sensor declared to Android, a profile that declares none, or
 * a run whose activation failed) and otherwise left out until its first reading.
 *
 * [reported] comes from storage that survives restarts and app updates, and [onFirstReading] writes it,
 * so the answer does not restart from nothing at every boot.
 *
 * A verdict outlives the run that reached it. Ending a run carries no new information about the
 * hardware, so [stop] cancels the acquire window and leaves the answer alone.
 *
 * Pure of Android: [schedule] supplies the acquire timer, so the state machine is directly testable.
 */
internal class LightAvailabilityTracker(
    present: Boolean,
    private val acquireTimeoutMs: Long,
    private val schedule: (Long, () -> Unit) -> ActivationRetryCancellation,
    reported: Boolean = false,
    private val declaredAbsent: Boolean = false,
    private val onFirstReading: () -> Unit = {},
    private val onChange: () -> Unit = {},
) {
    @Volatile
    var state: LightAvailability = if (present) LightAvailability.IDLE else LightAvailability.ABSENT
        private set

    private val absent = !present

    @Volatile
    private var reported = reported
    private val lock = Any()
    private var generation = 0L
    private var scheduled: ActivationRetryCancellation? = null

    /**
     * The single shared answer every advertising path reads: true describes the illuminance channel,
     * false states it absent, null leaves it out until the answer is settled.
     */
    fun channel(): Boolean? = when {
        reported -> true
        absent || declaredAbsent || state == LightAvailability.UNAVAILABLE -> false
        else -> null
    }

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

    /** A reading arrived: the sensor is real, whatever it did before, and stays described from now on. */
    fun reading() {
        val first = !reported
        mutate {
            state = LightAvailability.AVAILABLE
            reported = true
        }
        if (first && !absent) onFirstReading()
    }

    /** End the run. The verdict stands until the next run produces a new one. */
    fun stop() = mutate { }

    private fun expire(expectedGeneration: Long) = mutate(guard = { generation == expectedGeneration }) {
        if (state == LightAvailability.ACQUIRING) state = LightAvailability.UNAVAILABLE
    }

    /**
     * Applies a state change, cancelling any pending acquire window first, and reports afterwards
     * only when the [channel] answer actually moved. The notification carries no truth, only the
     * fact that consumers must re-read; it runs outside the lock so a consumer cannot re-enter it.
     */
    private fun mutate(guard: () -> Boolean = { true }, block: () -> Unit) {
        if (absent) return
        val moved = synchronized(lock) {
            if (!guard()) return
            val before = channel()
            generation++
            scheduled?.cancel()
            scheduled = null
            block()
            before != channel()
        }
        if (moved) onChange()
    }
}
