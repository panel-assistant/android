package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfProcessRankingTest {
    @Test fun cpuRankingUsesPositiveIntervalDeltasWithStablePidTies() {
        val previous = mapOf(10 to 100L, 20 to 200L, 30 to 300L, 40 to 400L)
        val current = mapOf(10 to 130L, 20 to 200L, 30 to 350L, 40 to 450L, 50 to 999L)

        assertEquals(
            listOf(30 to 50L, 40 to 50L, 10 to 30L),
            PerfReader.rankCpuProcesses(current, previous),
        )
    }

    @Test fun ramRankingUsesCurrentResidentPagesAndDropsEmptyRows() {
        assertEquals(
            listOf(7 to 900L, 8 to 900L, 9 to 800L, 6 to 700L, 5 to 600L),
            PerfReader.rankRamProcesses(
                mapOf(3 to 128L, 8 to 900L, 7 to 900L, 9 to 800L, 6 to 700L, 5 to 600L, 4 to 500L, 2 to 0L, 1 to -1L),
            ),
        )
    }

    @Test fun residentPagesUseTheRuntimePageSizeAndFamiliarMbValue() {
        assertEquals(1.0, PerfReader.rssMb(256L, 4096L), 0.0001)
        assertEquals(4.0, PerfReader.rssMb(256L, 16_384L), 0.0001)
        assertEquals(1.5, PerfReader.rssMb(384L, 4096L), 0.0001)
    }
}
