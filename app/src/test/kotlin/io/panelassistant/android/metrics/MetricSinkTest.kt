package io.panelassistant.android.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RamRingSink] must behave exactly like PerfReader's old fixed-size `ArrayDeque` history — a per-key FIFO
 * capped at N, dropping the oldest once full, oldest-first on read-out.
 */
class MetricSinkTest {

    @Test fun keepsAtMostCapacityDroppingOldestFifo() {
        val sink = RamRingSink(3)
        for (i in 1..5) sink.record(MetricSample.num("cpu", i.toDouble(), ts = i.toLong()))
        // Old ArrayDeque: addLast then removeFirst while size>cap → last 3, oldest-first.
        assertEquals(listOf(3.0, 4.0, 5.0), sink.history("cpu").map { it.num })
    }

    @Test fun exactlyMatchesAnArrayDequeFifoAcrossManyPushes() {
        val cap = 120
        val sink = RamRingSink(cap)
        val reference = ArrayDeque<Int>()
        for (i in 0 until 500) {
            sink.record(MetricSample.num("cpu", i.toDouble(), ts = i.toLong()))
            reference.addLast(i)
            while (reference.size > cap) reference.removeFirst()
        }
        assertEquals(reference.toList(), sink.history("cpu").map { it.num!!.toInt() })
    }

    @Test fun keysAreIndependent() {
        val sink = RamRingSink(2)
        sink.record(MetricSample.num("cpu", 1.0, ts = 1))
        sink.record(MetricSample.num("mem.percent", 2.0, ts = 1))
        sink.record(MetricSample.num("cpu", 3.0, ts = 2))
        assertEquals(listOf(1.0, 3.0), sink.history("cpu").map { it.num })
        assertEquals(listOf(2.0), sink.history("mem.percent").map { it.num })
        assertEquals(emptyList<Double>(), sink.history("gpu.load").map { it.num })
    }
}
