package io.panelassistant.android.sensors

internal const val ACTIVATION_RETRY_INITIAL_MS = 1_000L
internal const val ACTIVATION_RETRY_MAX_MS = 60_000L

internal fun activationRetryDelayMs(failureCount: Int): Long {
    var delay = ACTIVATION_RETRY_INITIAL_MS
    repeat(failureCount.coerceIn(0, 30)) {
        delay = (delay * 2L).coerceAtMost(ACTIVATION_RETRY_MAX_MS)
    }
    return delay
}

internal enum class ActivationRegistrationState {
    IDLE,
    RETRYING,
    REGISTERED,
    STOPPED,
}

internal fun interface ActivationRetryCancellation {
    fun cancel()
}

/**
 * Owns one activation-only SensorManager registration for one SensorReporter run. Registration
 * failures retry with capped exponential delay; stop invalidates the generation and cancels its timer.
 * The lifecycle deliberately has no sensor-value callback, so activation-only events cannot be
 * published as the legacy Android environmental entities.
 */
internal class ActivationRegistrationLifecycle(
    private val register: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> ActivationRetryCancellation,
    private val onState: (ActivationRegistrationState) -> Unit = {},
) {
    @Volatile
    var state: ActivationRegistrationState = ActivationRegistrationState.IDLE
        private set

    private var running = false
    private var generation = 0L
    private var scheduled: ActivationRetryCancellation? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        attempt(generation, failureCount = 0)
    }

    @Synchronized
    fun stop() {
        if (!running && state == ActivationRegistrationState.STOPPED) return
        running = false
        generation++
        scheduled?.cancel()
        scheduled = null
        setState(ActivationRegistrationState.STOPPED)
    }

    @Synchronized
    private fun attempt(expectedGeneration: Long, failureCount: Int) {
        if (!running || generation != expectedGeneration) return
        scheduled = null
        if (register()) {
            setState(ActivationRegistrationState.REGISTERED)
            return
        }
        setState(ActivationRegistrationState.RETRYING)
        scheduled = schedule(activationRetryDelayMs(failureCount)) {
            attempt(expectedGeneration, failureCount + 1)
        }
    }

    private fun setState(next: ActivationRegistrationState) {
        state = next
        onState(next)
    }
}
