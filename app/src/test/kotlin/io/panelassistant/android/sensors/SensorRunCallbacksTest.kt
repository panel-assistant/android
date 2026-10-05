package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensorRunCallbacksTest {
    private data class Events(
        val lux: MutableList<Int> = mutableListOf(),
        val rawLux: MutableList<Float> = mutableListOf(),
        val proximity: MutableList<Pair<Boolean?, Int?>> = mutableListOf(),
        val gestures: MutableList<Unit> = mutableListOf(),
        val approaches: MutableList<Unit> = mutableListOf(),
        val temperature: MutableList<Float> = mutableListOf(),
        val humidity: MutableList<Float> = mutableListOf(),
    )

    private fun run(events: Events) = SensorRunCallbacks(
        onLux = events.lux::add,
        onLuxRaw = events.rawLux::add,
        onProximity = { near, level, _ -> events.proximity += near to level },
        onGesture = { events.gestures += Unit },
        onTemperature = events.temperature::add,
        onHumidity = events.humidity::add,
        onPresenceApproach = { events.approaches += Unit },
    )

    @Test fun firstReadingsPublishAndCadenceUsesElapsedTime() {
        val events = Events()
        val run = run(events)

        run.light(10f, now = 100)
        run.light(11f, now = 20_000)
        run.light(20f, now = 20_100)
        run.temperature(20f, now = 100)
        run.temperature(21f, now = 30_000)
        run.temperature(21f, now = 60_100)
        run.humidity(40f, now = 100)
        run.humidity(42f, now = 60_100)

        assertEquals(listOf(10, 20), events.lux)
        assertEquals(listOf(10f, 11f, 20f), events.rawLux)
        assertEquals(listOf(20f, 21f), events.temperature)
        assertEquals(listOf(40f, 42f), events.humidity)
    }

    @Test fun closedGenerationRejectsEveryLateCallback() {
        val oldEvents = Events()
        val old = run(oldEvents)
        old.close()
        val replacementEvents = Events()
        val replacement = run(replacementEvents)

        old.light(50f, 100_000)
        old.proximity(true, 100)
        old.gesture()
        old.presenceApproach()
        old.temperature(22f, 100_000)
        old.humidity(45f, 100_000)
        replacement.light(12f, 100)
        replacement.proximity(false, 0)

        assertFalse(old.isOpen())
        assertEquals(Events(), oldEvents)
        assertEquals(listOf(12), replacementEvents.lux)
        assertEquals(listOf(false to 0), replacementEvents.proximity)
    }

    @Test fun reportingPresenceDoesNotInventAnApproachOrWave() {
        val events = Events()
        val callbacks = run(events)
        callbacks.proximity(true, 100)
        assertEquals(emptyList<Unit>(), events.approaches)
        assertEquals(emptyList<Unit>(), events.gestures)
        callbacks.presenceApproach()
        assertEquals(listOf(Unit), events.approaches)
        assertEquals(emptyList<Unit>(), events.gestures)
    }

    @Test fun aClockRollbackCannotFreezeAChangedReading() {
        val events = Events()
        val run = run(events)
        run.light(10f, now = 100_000)
        run.light(20f, now = 90_000)

        assertEquals(listOf(10, 20), events.lux)
    }

    @Test fun proximityStartupPublishesUnknownBeforeAnImmediateSourceValue() {
        val events = Events()
        val run = run(events)

        val active = initializeProximitySource(run, isCurrent = { true }) {
            run.proximity(true, 100)
        }

        assertEquals(true, active)
        assertEquals(listOf(null to null, true to 100), events.proximity)
    }

    @Test fun proximityStartupDoesNotActivateAfterInitialCallbackClosesRun() {
        val events = Events()
        lateinit var run: SensorRunCallbacks
        run = SensorRunCallbacks(
            onLux = events.lux::add,
            onLuxRaw = events.rawLux::add,
            onProximity = { near, level, _ ->
                events.proximity += near to level
                run.close()
            },
            onGesture = { events.gestures += Unit },
            onTemperature = events.temperature::add,
            onHumidity = events.humidity::add,
        onPresenceApproach = { events.approaches += Unit },
        )
        var activated = false

        val active = initializeProximitySource(run, isCurrent = { true }) { activated = true }

        assertFalse(active)
        assertFalse(activated)
        assertEquals(listOf(null to null), events.proximity)
    }
}
