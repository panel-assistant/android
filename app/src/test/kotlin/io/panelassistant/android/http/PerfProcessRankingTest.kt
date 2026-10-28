package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** One top sample per call, driven through the real publication path with full names resolved. */
    private class Sampler(private val generation: Long) {
        private val publish = PerfReader::class.java.getDeclaredMethod("publishTop", java.lang.Long.TYPE,
            Map::class.java, Map::class.java, Map::class.java, java.lang.Long.TYPE, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }
        private var total = 0L

        /** [procs] maps pid to (comm, jiffies); every process holds 256 resident pages. */
        fun sample(vararg procs: Pair<Int, Pair<String, Long>>) {
            total += 1_000L
            val map = procs.toMap()
            publish.invoke(PerfReader, generation, map.mapValues { it.value.second },
                map.mapValues { 256L }, map.mapValues { it.value.first }, total, true)
        }
    }

    private fun workload(names: List<String>) = names.filterNot { it.startsWith("sampling probes") }

    private fun names(key: String): List<String> {
        val rows = org.json.JSONObject(PerfReader.json()).optJSONArray(key) ?: return emptyList()
        return workload((0 until rows.length()).map { rows.getJSONObject(it).getString("name") })
    }

    @Test fun aReusedPidNeverInheritsTheDeadProcessNameOrCpuBaseline() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        // The resolver answers with whatever process currently owns each pid, as /proc/<pid>/cmdline would.
        val owner = HashMap<Int, String>()
        val resolved = ArrayList<Int>()
        val idle = kotlinx.coroutines.channels.Channel<Unit>()
        try {
            PerfReader.stop()
            PerfReader.startForTest(scope, rootAvailable = { false }, elapsedRealtime = { 1L },
                pause = { idle.receive() },
                resolveNames = { pids -> resolved += pids; pids.mapNotNull { p -> owner[p]?.let { p to it } }.toMap() })
            val s = Sampler(PerfReader.lifecycleGeneration())

            // First sample: no CPU interval yet, RAM rows resolved to full names.
            owner[100] = "com.old.app"; owner[200] = "com.steady.app"; owner[300] = "com.gone.app"
            s.sample(100 to ("old" to 10L), 200 to ("steady" to 10L), 300 to ("gone" to 10L))
            assertEquals(listOf("com.gone.app", "com.old.app", "com.steady.app"), names("topRam").sorted())
            s.sample(100 to ("old" to 20L), 200 to ("steady" to 30L), 300 to ("gone" to 15L))
            assertEquals(listOf("com.steady.app", "com.old.app", "com.gone.app"), names("top"))

            // pid 100 is reused by a different process (different comm) with a higher counter, and pid 300 exits.
            owner[100] = "com.new.app"; owner.remove(300)
            resolved.clear()
            s.sample(100 to ("new" to 500L), 200 to ("steady" to 50L))
            assertEquals("the new process is named afresh", listOf("com.new.app", "com.steady.app"), names("topRam").sorted())
            assertEquals("the dead process's baseline is not charged to the new one", listOf("com.steady.app"), names("top"))
            assertEquals(listOf("com.steady.app"), workload(PerfReader.topNames()))
            assertEquals("only the reused pid is resolved again; an unchanged process keeps its name", listOf(100), resolved)

            // The new process ranks from its own baseline on the next interval.
            s.sample(100 to ("new" to 540L), 200 to ("steady" to 60L))
            assertEquals(listOf("com.new.app", "com.steady.app"), names("top"))
            assertEquals(listOf("com.new.app", "com.steady.app"), workload(PerfReader.topNames()))

            // pid 300 comes back with the same comm after a sample without it: its old name is not reused.
            owner[300] = "com.returned.app"
            resolved.clear()
            s.sample(100 to ("new" to 550L), 200 to ("steady" to 70L), 300 to ("gone" to 5L))
            assertTrue("com.returned.app" in names("topRam"))
            assertEquals(listOf(300), resolved)

            // Going idle clears the cached names and baselines, so the next sample starts over.
            assertTrue(idle.trySend(Unit).isSuccess)
            resolved.clear()
            s.sample(100 to ("new" to 600L), 200 to ("steady" to 80L), 300 to ("gone" to 9L))
            assertEquals(listOf(100, 200, 300), resolved.sorted())
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
