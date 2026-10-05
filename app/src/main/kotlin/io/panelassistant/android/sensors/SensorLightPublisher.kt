package io.panelassistant.android.sensors

import io.panelassistant.android.util.LatestDispatcher
import io.panelassistant.android.util.submit

internal fun submitIlluminanceIfExposed(
    exposed: Boolean,
    lux: Int,
    submit: (Int) -> Unit,
): Boolean {
    if (!exposed) return false
    submit(lux)
    return true
}

/**
 * Keeps MQTT lifecycle contention off Android's sensor callback thread.
 *
 * A stalled publication retains at most the newest pending light sample. Raw samples can therefore
 * continue feeding local auto-brightness even while the broker runtime is rebuilding or retiring.
 */
internal class SensorLightPublisher(
    publish: (Int) -> Unit,
    threadName: String = "ha-paneld-light-mqtt",
) : AutoCloseable {
    private val dispatcher = LatestDispatcher.singleSlot(
        threadName = threadName,
        consume = publish,
    )

    fun submit(lux: Int) {
        dispatcher.submit(lux)
    }

    fun awaitTermination(timeoutMs: Long): Boolean = dispatcher.awaitTermination(timeoutMs)

    override fun close() = dispatcher.close()
}
