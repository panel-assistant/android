package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YC-SM10P declares an `em3071x` light part its bus never answers for. `getDefaultSensor` still
 * returns it, so presence advertised an illuminance entity that stored 50 empty Home Assistant states
 * over 30 days while the HAL logged `Error activating sensor 3`. These tests pin the distinction
 * between a declared part and a working one.
 */
class LightAvailabilityTest {
    /** Collects scheduled acquire timers so a test fires or cancels them explicitly. */
    private class Timers {
        val pending = ArrayDeque<Pair<Long, () -> Unit>>()
        var cancelled = 0

        fun schedule(delayMs: Long, task: () -> Unit): ActivationRetryCancellation {
            val entry = delayMs to task
            pending.addLast(entry)
            return ActivationRetryCancellation {
                if (pending.remove(entry)) cancelled++
            }
        }

        fun fireAll() {
            while (pending.isNotEmpty()) pending.removeFirst().second.invoke()
        }
    }

    private fun tracker(present: Boolean = true, timers: Timers = Timers(), onChange: () -> Unit = {}) =
        LightAvailabilityTracker(
            present = present,
            acquireTimeoutMs = ACQUIRE_MS,
            schedule = timers::schedule,
            onChange = onChange,
        )

    @Test fun `a refused registration is unavailable and says so in the start log`() {
        var changes = 0
        val subject = tracker(onChange = { changes++ })

        // Optimistic before the answer is known: a healthy panel must not withdraw its entity at boot.
        assertTrue(subject.available())

        subject.registered(ok = false)

        assertFalse(subject.available())
        assertEquals(LightAvailability.UNAVAILABLE, subject.state)
        // The start log reports what activated, not what the device tree declared.
        assertEquals("failed", subject.label())
        assertEquals(1, changes)
    }

    @Test fun `a working sensor stays available and schedules no withdrawal`() {
        var changes = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onChange = { changes++ })

        subject.registered(ok = true)
        assertEquals(LightAvailability.ACQUIRING, subject.state)
        assertEquals(ACQUIRE_MS, timers.pending.single().first)

        subject.reading()

        assertTrue(subject.available())
        assertEquals(LightAvailability.AVAILABLE, subject.state)
        assertEquals("available", subject.label())
        // The acquire window is cancelled, so firing every remaining timer cannot withdraw a live sensor.
        assertEquals(1, timers.cancelled)
        timers.fireAll()
        assertTrue(subject.available())
        // Available throughout: nothing was ever withdrawn, so no re-announce was requested.
        assertEquals(0, changes)
    }

    @Test fun `a registration that never reports stops being advertised when the window expires`() {
        var changes = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onChange = { changes++ })

        subject.registered(ok = true)
        assertTrue(subject.available())

        timers.fireAll()

        assertFalse(subject.available())
        assertEquals(LightAvailability.UNAVAILABLE, subject.state)
        assertEquals("failed", subject.label())
        assertEquals(1, changes)
    }

    @Test fun `a late reading restores a sensor that only woke slowly`() {
        var changes = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onChange = { changes++ })

        subject.registered(ok = true)
        timers.fireAll()
        assertFalse(subject.available())

        subject.reading()

        assertTrue(subject.available())
        assertEquals(LightAvailability.AVAILABLE, subject.state)
        // Withdrawn once, restored once: each transition re-announces exactly once.
        assertEquals(2, changes)
    }

    @Test fun `an expired timer from a previous run cannot withdraw the current one`() {
        val timers = Timers()
        val subject = tracker(timers = timers)

        subject.registered(ok = true)
        val staleWindow = timers.pending.single().second
        subject.stop()
        subject.registered(ok = true)
        subject.reading()

        staleWindow.invoke()

        assertTrue(subject.available())
        assertEquals(LightAvailability.AVAILABLE, subject.state)
    }

    @Test fun `stop returns the tracker to an undecided state for the next run`() {
        val timers = Timers()
        val subject = tracker(timers = timers)

        subject.registered(ok = false)
        assertFalse(subject.available())

        subject.stop()

        assertEquals(LightAvailability.IDLE, subject.state)
        assertTrue(subject.available())
    }

    @Test fun `no declared sensor is absent and no registration result can revive it`() {
        var changes = 0
        val subject = tracker(present = false, onChange = { changes++ })

        assertFalse(subject.available())
        assertEquals("absent", subject.label())

        subject.registered(ok = true)
        subject.reading()

        assertFalse(subject.available())
        assertEquals(LightAvailability.ABSENT, subject.state)
        assertEquals(0, changes)
    }

    private companion object {
        const val ACQUIRE_MS = 5_000L
    }
}
