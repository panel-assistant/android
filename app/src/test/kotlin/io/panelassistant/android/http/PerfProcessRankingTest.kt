package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfProcessRankingTest {
    @Test fun processNamesRoundTripThroughThePublishedCpuAndRamJson() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            PerfReader.stop()
            PerfReader.startForTest(scope, rootAvailable = { false }, elapsedRealtime = { 1L }, pause = { kotlinx.coroutines.awaitCancellation() })
            val publish = PerfReader::class.java.getDeclaredMethod("publishTop", java.lang.Long.TYPE,
                Map::class.java, Map::class.java, Map::class.java, java.lang.Long.TYPE, java.lang.Boolean.TYPE).apply { isAccessible = true }
            val name = "worker\t\"<\\name"
            val generation = PerfReader.lifecycleGeneration()
            publish.invoke(PerfReader, generation, mapOf(12345 to 10L), mapOf(12345 to 256L), mapOf(12345 to name), 100L, false)
            publish.invoke(PerfReader, generation, mapOf(12345 to 20L), mapOf(12345 to 256L), mapOf(12345 to name), 200L, false)
            val payload = PerfReader.json()
            val json = org.json.JSONObject(payload)
            assertEquals(name, json.getJSONArray("top").getJSONObject(0).getString("name"))
            assertEquals(name, json.getJSONArray("topRam").getJSONObject(0).getString("name"))
            org.junit.Assert.assertFalse("JSON strings must escape control characters", payload.any { it.code < 32 })
        } finally {
            PerfReader.stop()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

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
