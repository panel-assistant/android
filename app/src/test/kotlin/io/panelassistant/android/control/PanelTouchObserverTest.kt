package io.panelassistant.android.control

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelTouchObserverTest {
    @Test fun `one observed touch fans out once to independent subscribers`() {
        val registry = TouchListenerRegistry()
        var soundEvents = 0
        var presenceEvents = 0
        registry.add { soundEvents++ }
        registry.add { presenceEvents++ }

        registry.dispatch()

        assertEquals(1, soundEvents)
        assertEquals(1, presenceEvents)
    }

    @Test fun `removing one subscriber leaves the other active`() {
        val registry = TouchListenerRegistry()
        var soundEvents = 0
        var presenceEvents = 0
        val sound = registry.add { soundEvents++ }
        registry.add { presenceEvents++ }

        registry.remove(sound)
        registry.dispatch()

        assertEquals(0, soundEvents)
        assertEquals(1, presenceEvents)
    }

    @Test fun `subscriber mutation during dispatch applies to the next touch`() {
        val registry = TouchListenerRegistry()
        val events = mutableListOf<String>()
        lateinit var removeFirst: () -> Unit
        val first = registry.add {
            events += "first"
            removeFirst()
        }
        removeFirst = { registry.remove(first) }
        registry.add { events += "second" }

        registry.dispatch()
        registry.dispatch()

        assertEquals(listOf("first", "second", "second"), events)
    }

    @Test fun `inactive timed-out subscriber cannot receive a touch before cleanup`() {
        val registry = TouchListenerRegistry()
        val active = AtomicBoolean(true)
        var events = 0
        registry.add(active) { events++ }

        active.set(false)
        registry.dispatch()
        registry.removeInactive()

        assertEquals(0, events)
        assertEquals(true, registry.isEmpty())
    }

    @Test fun `one failing subscriber does not block the remaining subscribers`() {
        val registry = TouchListenerRegistry()
        var events = 0
        registry.add { error("broken listener") }
        registry.add { events++ }

        registry.dispatch()

        assertEquals(1, events)
    }
}
