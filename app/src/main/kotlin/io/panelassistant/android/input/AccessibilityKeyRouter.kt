package io.panelassistant.android.input

import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.profile.ProfileProximityKeys

/** Routes Android input by its stable device name and Linux scan code, never event-node numbering. */
internal object AccessibilityKeyRouter {
    @Volatile var buttons: List<EvdevButton> = emptyList()
    @Volatile private var declaredProximity: ProfileProximityKeys? = null
    private data class Proximity(val keys: ProfileProximityKeys, val emit: (Float) -> Unit, val unavailable: () -> Unit)
    @Volatile private var proximity: Proximity? = null

    fun configure(profile: io.panelassistant.android.device.DeviceProfile) {
        buttons = profile.evdevButtons.filter { it.deviceName != null }
        declaredProximity = profile.proximityKeys
    }

    @Synchronized fun subscribeProximity(keys: ProfileProximityKeys, onUnavailable: () -> Unit = {}, emit: (Float) -> Unit): AutoCloseable {
        val registration = Proximity(keys, emit, onUnavailable)
        declaredProximity = keys
        proximity = registration
        return AutoCloseable { synchronized(this) { if (proximity === registration) proximity = null } }
    }

    fun disconnected() { proximity?.unavailable?.invoke() }

    fun dispatch(deviceName: String?, scanCode: Int, down: Boolean, repeatCount: Int, eventType: String): Boolean {
        val sensor = proximity
        val sensorCode = declaredProximity?.let { scanCode == it.nearCode || scanCode == it.farCode } == true
        if (sensorCode) {
            if (sensor != null && deviceName == sensor.keys.deviceName && down && repeatCount == 0) {
                sensor.emit(if (scanCode == sensor.keys.nearCode) 1f else 0f)
            }
            return deviceName == declaredProximity?.deviceName // Consume only this sensor's pulses, on both edges and repeats.
        }
        val named = buttons
        val mapped = named.firstOrNull { it.deviceName == deviceName && it.code == scanCode }
        if (mapped != null) {
            when {
                down && repeatCount == 0 -> ButtonBus.emit(mapped.eventType)
                !down && mapped.release -> ButtonBus.emit(mapped.eventType + EvdevStreamSession.RELEASED_SUFFIX)
            }
            return false
        }
        // A mapped key from another device is not this panel's physical button.
        if (named.any { it.code == scanCode || it.eventType == eventType }) return false
        ButtonEventPolicy.accessibility(down, repeatCount, eventType)?.let(ButtonBus::emit)
        return false
    }
}
