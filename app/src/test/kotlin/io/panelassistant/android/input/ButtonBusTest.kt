package io.panelassistant.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonBusTest {
    @Test fun staleOwnerCannotClearReplacementListener() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val stale = ButtonBus.subscribe(first::add)
        val current = ButtonBus.subscribe(second::add)

        stale.close()
        ButtonBus.emit("KEYCODE_F1")

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("KEYCODE_F1"), second)
        current.close()
    }

    @Test fun currentOwnerCloseStopsDeliveryAndIsIdempotent() {
        val events = mutableListOf<String>()
        val subscription = ButtonBus.subscribe(events::add)
        ButtonBus.emit("KEYCODE_POWER")
        subscription.close()
        subscription.close()
        ButtonBus.emit("KEYCODE_MUTE")
        assertEquals(listOf("KEYCODE_POWER"), events)
    }
}
