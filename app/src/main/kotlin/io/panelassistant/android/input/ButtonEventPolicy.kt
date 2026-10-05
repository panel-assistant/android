package io.panelassistant.android.input

/** Pure event-shaping rules shared by the Android and helper-backed physical-button sources. */
internal object ButtonEventPolicy {
    /** Android sends repeat ACTION_DOWN events while a key is held; one physical press is one HA event. */
    fun accessibility(down: Boolean, repeatCount: Int, eventType: String): String? =
        eventType.takeIf { down && repeatCount == 0 }
}
