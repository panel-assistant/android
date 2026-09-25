package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.metrics.MetricRegistry
import io.github.maxlyth.hapaneld.metrics.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfProjectionTest {
    private fun snapshot(
        cpu: Int? = null,
        memUsed: Long = 600,
        memTotal: Long = 1000,
        memPercent: Int? = 60,
        gpu: Int = -1,
    ) = Snapshot(
        ts = 1234,
        cpuOverall = cpu,
        cpuCores = emptyList(),
        memUsedMb = memUsed,
        memTotalMb = memTotal,
        memPercent = memPercent,
        socTempC = 42.5,
        gpuPct = gpu,
        gpuMhz = if (gpu >= 0) 800 else 0,
        loadavg = listOf("0.5", "0.4", "0.3"),
        freqCurMhz = listOf(1416),
        freqMaxMhz = 1800,
        dump = null,
    )

    @Test fun cpuAbsenceDoesNotSuppressIndependentMetricsOrFabricateGpuHistory() {
        val projection = PerfReader.projectSnapshot(snapshot())
        val keys = projection.samples.map { it.key }.toSet()

        assertTrue("\"cpu\":null" in projection.fields)
        assertTrue("\"tempC\":42.5" in projection.fields)
        assertTrue("\"memUsedMb\":600" in projection.fields)
        assertEquals(setOf(MetricRegistry.MEM.key), keys)
    }

    @Test fun unavailableMemoryStaysNullAndDoesNotCreateZeroHistory() {
        val projection = PerfReader.projectSnapshot(
            snapshot(cpu = 42, memUsed = 0, memTotal = 0, memPercent = null, gpu = 45),
        )
        val keys = projection.samples.map { it.key }.toSet()

        assertTrue("\"memUsedMb\":null" in projection.fields)
        assertTrue("\"memTotalMb\":null" in projection.fields)
        assertEquals(setOf(MetricRegistry.CPU.key, MetricRegistry.GPU_LOAD.key), keys)
        assertFalse(MetricRegistry.MEM.key in keys)
    }

    @Test fun pageActivityWindowRequiresATouchAndRejectsClockRollback() {
        assertFalse(PerfReader.withinActiveWindow(now = 10_000, lastAccess = 0))
        assertTrue(PerfReader.withinActiveWindow(now = 10_500, lastAccess = 10_000))
        assertFalse(PerfReader.withinActiveWindow(now = 9_000, lastAccess = 10_000))
    }
}
