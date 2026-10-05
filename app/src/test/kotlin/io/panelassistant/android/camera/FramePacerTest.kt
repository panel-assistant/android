package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-rate cap is enforced where frames enter the encoder, and it is a ceiling over any real
 * run: a window of N seconds admits at most N * fps frames plus the one on its opening boundary.
 */
class FramePacerTest {

    private class Run(val admitted: Int, val firstMs: Long, val lastMs: Long, val ticks: Int) {
        /** Frames per second between the first and last admitted frame. */
        val rate: Double get() = (admitted - 1) * 1_000.0 / (lastMs - firstMs)
    }

    /** Sensor ticks every [sensorMs] (alternately ± [jitterMs]) for [seconds]. */
    private fun drive(pacer: FramePacer, sensorMs: Long, seconds: Int, jitterMs: Long = 0L): Run {
        var count = 0
        var ticks = 0
        var first = -1L
        var last = -1L
        var t = 0L
        val end = seconds * 1_000L
        var flip = 1L
        while (t < end) {
            ticks++
            if (pacer.admit(t)) {
                count++
                if (first < 0) first = t
                last = t
            }
            flip = -flip
            t += sensorMs + flip * jitterMs
        }
        return Run(count, first, last, ticks)
    }

    @Test fun aThirtyFpsSensorPacedToFifteenDeliversExactlyFifteenPerSecondWithNoDrift() {
        val run = drive(FramePacer(fps = 15), sensorMs = 33L, seconds = 60)
        assertEquals("60 s of 15 fps plus the boundary frame", 60 * 15 + 1, run.admitted)
        // The slop lets the last frame land up to 16 ms early, which is the whole of the tolerance here.
        assertEquals(15.0, run.rate, 0.01)
    }

    @Test fun lowCapsHoldOverALongRunEvenWithSensorJitter() {
        val five = drive(FramePacer(fps = 5), sensorMs = 33L, seconds = 20, jitterMs = 4L)
        assertTrue("${five.admitted}", five.admitted <= 20 * 5 + 1)
        assertEquals(5.0, five.rate, 0.05)
        val one = drive(FramePacer(fps = 1), sensorMs = 33L, seconds = 30, jitterMs = 6L)
        assertTrue("${one.admitted}", one.admitted <= 30 * 1 + 1)
        assertEquals(1.0, one.rate, 0.01)
    }

    @Test fun aCapAtTheSensorRateNeverAdmitsMoreThanTheCapEvenWhenTheSensorRunsSlightlyFast() {
        // A 33 ms tick is 30.3 fps; the cap is a ceiling, so over five seconds at most 151 frames pass
        // of the 152 the sensor offered, and the pacer never falls a whole frame below the cap either.
        val run = drive(FramePacer(fps = 30), sensorMs = 33L, seconds = 5)
        assertEquals(152, run.ticks)
        assertTrue("${run.admitted}", run.admitted <= 5 * 30 + 1)
        assertTrue("${run.admitted}", run.admitted >= 5 * 30)
    }

    @Test fun theFirstFrameIsAdmittedAndTheNextOnlyAfterTheInterval() {
        val pacer = FramePacer(fps = 10)
        assertTrue(pacer.admit(1_000L))
        assertFalse(pacer.admit(1_033L))
        assertFalse(pacer.admit(1_066L))
        assertTrue("just inside the slop counts", pacer.admit(1_090L))
        assertFalse(pacer.admit(1_123L))
        assertTrue(pacer.admit(1_200L))
    }

    @Test fun aStallEarnsNoCreditSoTheCadenceNeverBursts() {
        val pacer = FramePacer(fps = 10)
        assertTrue(pacer.admit(0L))
        assertTrue("first frame after a long gap", pacer.admit(5_000L))
        assertFalse("but not a burst to catch up", pacer.admit(5_033L))
        assertFalse(pacer.admit(5_066L))
        assertTrue(pacer.admit(5_100L))
    }

    /**
     * The cadence the two camera boards actually deliver: 30 fps is 33.33 ms, which lands on 33, 33, 34
     * in whole milliseconds, with a millisecond of jitter either way. A bare "at least one interval since
     * the last delivery" gate — the owner's before this test existed — drops every frame that lands a
     * millisecond early and then waits a whole extra sensor frame, so it delivers ten of a fifteen cap
     * and twenty of a thirty cap. The pacer's slop is what makes the cap hold on real hardware.
     */
    @Test fun theRealSensorCadenceWithJitterStillDeliversTheWholeCap() {
        listOf(15 to 30, 30 to 30).forEach { (cap, sensor) ->
            val pacer = FramePacer(fps = cap)
            var t = 0L
            var admitted = 0
            var lastAdmitted = -1L
            var i = 0
            val end = 60_000L
            while (t < end) {
                if (pacer.admit(t)) { admitted++; lastAdmitted = t }
                // 33, 33, 34 keeps the long-run period at 33.33 ms; the jitter alternates -1, 0, +1.
                t += 1_000L / sensor + (if (i % 3 == 2) 1 else 0) + ((i % 3) - 1)
                i++
            }
            val rate = (admitted - 1) * 1_000.0 / lastAdmitted
            assertTrue("cap $cap from a $sensor fps sensor delivered $rate fps", rate >= cap * 0.99)
            assertTrue("never above the cap: $admitted", admitted <= 60 * cap + 1)
        }
    }
}
