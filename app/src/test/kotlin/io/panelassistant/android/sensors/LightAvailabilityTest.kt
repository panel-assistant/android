package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The YC-SM10P declares an `em3071x` light part its bus never answers for. `getDefaultSensor` still
 * returns it, so presence advertised an illuminance entity that stored 50 empty Home Assistant states
 * over 30 days while the HAL logged `Error activating sensor 3`. These tests pin the rule that
 * replaced presence (maintainer, 2026-10-06): a sensor is described once it has reported on this
 * install and kept after that; one that never reported is never described.
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

    private fun tracker(
        present: Boolean = true,
        reported: Boolean = false,
        declaredAbsent: Boolean = false,
        timers: Timers = Timers(),
        onFirstReading: () -> Unit = {},
        onChange: () -> Unit = {},
    ) = LightAvailabilityTracker(
        present = present,
        acquireTimeoutMs = ACQUIRE_MS,
        schedule = timers::schedule,
        reported = reported,
        declaredAbsent = declaredAbsent,
        onFirstReading = onFirstReading,
        onChange = onChange,
    )

    @Test fun `a sensor that has never reported is left out until it settles, then stated absent`() {
        var changes = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onChange = { changes++ })

        // Neither described nor stated absent while nothing is known.
        assertNull(subject.channel())
        subject.registered(ok = true)
        assertNull(subject.channel())

        timers.fireAll()

        assertEquals(false, subject.channel())
        assertEquals("failed", subject.label())
        assertEquals(1, changes)
    }

    @Test fun `a refused registration on a sensor that never reported is stated absent`() {
        val subject = tracker()

        subject.registered(ok = false)

        assertEquals(false, subject.channel())
        assertEquals(LightAvailability.UNAVAILABLE, subject.state)
    }

    @Test fun `the first reading describes the channel, records it once and asks for a re-announce`() {
        var changes = 0
        var recorded = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onFirstReading = { recorded++ }, onChange = { changes++ })

        subject.registered(ok = true)
        subject.reading()
        subject.reading()

        assertEquals(true, subject.channel())
        assertEquals("available", subject.label())
        assertEquals(1, recorded)
        assertEquals(1, changes)
        // The acquire window is cancelled, so firing every remaining timer cannot withdraw a live sensor.
        assertEquals(1, timers.cancelled)
        timers.fireAll()
        assertEquals(true, subject.channel())
    }

    @Test fun `a sensor that reported is kept through a later refused, silent or stopped run`() {
        var changes = 0
        val timers = Timers()
        val subject = tracker(timers = timers, onChange = { changes++ })
        subject.registered(ok = true)
        subject.reading()
        changes = 0

        subject.stop()
        subject.registered(ok = true)
        timers.fireAll()
        assertEquals(true, subject.channel())
        subject.registered(ok = false)
        assertEquals(true, subject.channel())
        assertEquals(LightAvailability.UNAVAILABLE, subject.state)
        assertEquals(0, changes)
    }

    @Test fun `a reading recorded by an earlier process keeps the channel from the first moment`() {
        // A restart or an app update builds a new tracker from the stored flag.
        var recorded = 0
        val subject = tracker(reported = true, onFirstReading = { recorded++ })

        assertEquals(true, subject.channel())
        subject.registered(ok = false)
        assertEquals(true, subject.channel())
        subject.reading()
        assertEquals(0, recorded)
    }

    @Test fun `a profile declaring no light sensor settles absence at once, and a reading still wins`() {
        val subject = tracker(declaredAbsent = true)

        assertEquals(false, subject.channel())
        subject.registered(ok = true)
        assertEquals(false, subject.channel())

        subject.reading()

        assertEquals(true, subject.channel())
    }

    @Test fun `an expired timer from a previous run cannot withdraw the current one`() {
        val timers = Timers()
        val subject = tracker(timers = timers)

        subject.registered(ok = true)
        val staleWindow = timers.pending.single().second
        subject.stop()
        // A second run is acquiring again, so only the generation guard can save it here.
        subject.registered(ok = true)

        staleWindow.invoke()

        assertEquals(LightAvailability.ACQUIRING, subject.state)
        assertNull(subject.channel())
    }

    @Test fun `stop keeps the verdict, so a dead sensor is not re-announced at every restart`() {
        var changes = 0
        val subject = tracker(onChange = { changes++ })

        subject.registered(ok = false)
        assertEquals(1, changes)

        subject.stop()
        subject.registered(ok = false)

        assertEquals(false, subject.channel())
        assertEquals(1, changes)
    }

    @Test fun `no declared sensor is absent and no registration result can revive it`() {
        var changes = 0
        var recorded = 0
        val subject = tracker(present = false, onFirstReading = { recorded++ }, onChange = { changes++ })

        assertEquals(false, subject.channel())
        assertEquals("absent", subject.label())

        subject.registered(ok = true)
        subject.reading()

        assertEquals(false, subject.channel())
        assertEquals(LightAvailability.ABSENT, subject.state)
        assertEquals(0, changes)
        assertEquals(0, recorded)
    }

    private companion object {
        const val ACQUIRE_MS = 5_000L
    }
}
