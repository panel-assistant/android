package io.github.maxlyth.hapaneld.assist

/** What the voice assistant is doing. Home Assistant sees it as the satellite entity's state. */
enum class VoiceState {
    OFF, IDLE, LISTENING, PROCESSING, RESPONDING, ERROR;

    val wireValue: String get() = name.lowercase()
}

/**
 * Single authoritative "what is the voice assistant doing right now" flag.
 *
 * Owned here so the settings/HA surface can exist before the voice-pipeline lane is wired up: that lane
 * drives it via [set] once it exists. Default [VoiceState.OFF] — a panel with voice_enabled off, or
 * before any pipeline has run, reports OFF rather than an invented state. [setChangeListener] follows the
 * same pattern as `SensorReporter.setLearnedProximityListener`: the listener is expected to resolve the
 * CURRENT bridge generation (e.g. `runtime.observe()?.value?.mqtt`) rather than close over one, so a
 * bridge rebuild (reconfigure) never leaves a stale generation publishing.
 */
class VoiceStateAuthority {
    @Volatile private var state: VoiceState = VoiceState.OFF
    @Volatile private var listener: (() -> Unit)? = null

    fun current(): VoiceState = state

    /** Report a transition. Notifies [setChangeListener]'s callback only on an actual change, so a
     *  coordinator that re-asserts the same phase every tick does not flood a state-converger publish. */
    @Synchronized
    fun set(next: VoiceState) {
        if (state == next) return
        state = next
        listener?.invoke()
    }

    fun setChangeListener(onChange: () -> Unit) {
        listener = onChange
    }
}
