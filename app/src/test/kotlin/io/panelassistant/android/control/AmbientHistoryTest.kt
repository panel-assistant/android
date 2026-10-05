package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln1p

class AmbientHistoryTest {
    @Test fun durationCrossingBoundaryIsSplitWithoutLosingWeight() {
        val accumulator = AmbientHistoryAccumulator()
        val completed = accumulator.add("location", "local", 59_000, 100.0, 2_000, baselineEligible = true)

        assertEquals(1, completed.size)
        assertEquals(1_000, completed.single().coverageMs)
        assertEquals(100_000.0, completed.single().luxIntegral, 0.001)
        val tail = accumulator.drain().single()
        assertEquals(1_000, tail.coverageMs)
        assertEquals(ln1p(100.0) * 1_000, tail.baselineLogIntegral, 0.001)
    }

    @Test fun invalidEvidenceIsRejectedAndAccumulatorRemainsBounded() {
        val accumulator = AmbientHistoryAccumulator(maxEntries = 2)
        assertTrue(accumulator.add("", "local", 0, 1.0, 1_000, true).isEmpty())
        assertTrue(accumulator.add("a", "local", 0, Double.NaN, 1_000, true).isEmpty())
        accumulator.add("a", "local", 0, 1.0, 1_000, true)
        accumulator.add("b", "local", 0, 2.0, 1_000, true)
        val evicted = accumulator.add("c", "local", 0, 3.0, 1_000, true)
        assertEquals(1, evicted.size)
        assertEquals(2, accumulator.size())
    }
}
