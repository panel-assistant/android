package io.panelassistant.android.http

import io.panelassistant.android.RendererTarget
import io.panelassistant.android.metrics.PerfDump
import io.panelassistant.android.metrics.ProcessMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PerfLifecycleTest {
    private fun idleScope(): Triple<CoroutineScope, () -> Unit, () -> Unit> {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        // An idle sampler (no touch) never ticks, so the loop just pauses — fine for lifecycle assertions.
        return Triple(scope, { scope.cancel(); dispatcher.close(); executor.shutdownNow() }, {})
    }

    @Test fun rendererTargetIsInstalledUnderAdmissionAndClearedOnStop() {
        val (scope, teardown, _) = idleScope()
        try {
            PerfReader.stop()
            assertNull("stop clears the snapshot", PerfReader.rendererTargetForTest())

            PerfReader.start(scope, ownPackage = "io.github.maxlyth.hapaneld", initialTarget = RendererTarget.Builtin)
            assertEquals("start installs the initial snapshot under admission",
                RendererTarget.Builtin, PerfReader.rendererTargetForTest())

            PerfReader.stop()
            assertNull("stop clears the snapshot", PerfReader.rendererTargetForTest())
        } finally {
            PerfReader.stop(); teardown()
        }
    }

    @Test fun updateRendererTargetIsGenerationGuarded() {
        val (scope, teardown, _) = idleScope()
        try {
            PerfReader.stop()
            // Before any start: no active generation, so an update must not publish.
            PerfReader.updateRendererTarget(RendererTarget.Foreign("com.example.before"))
            assertNull("update before start is a no-op", PerfReader.rendererTargetForTest())

            PerfReader.start(scope, ownPackage = "own", initialTarget = null)
            PerfReader.updateRendererTarget(RendererTarget.Foreign("com.example.live"))
            assertEquals("update while running publishes at the config boundary",
                RendererTarget.Foreign("com.example.live"), PerfReader.rendererTargetForTest())

            PerfReader.stop()
            PerfReader.updateRendererTarget(RendererTarget.Foreign("com.example.after"))
            assertNull("update after stop is a no-op — a dead generation can't publish",
                PerfReader.rendererTargetForTest())
        } finally {
            PerfReader.stop(); teardown()
        }
    }

    /** Run the sampler through [ticks] active ticks with [perfDump] as the helper, then stop it. */
    private fun sampleActively(
        ticks: Int,
        afterSample: () -> Unit = {},
        perfDump: () -> PerfDump?,
    ): String {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val paused = Channel<Unit>(Channel.UNLIMITED)
        val releases = Channel<Unit>(Channel.UNLIMITED)
        try {
            PerfReader.stop()
            PerfReader.startForTest(
                scope = scope,
                perfDump = perfDump,
                elapsedRealtime = { 100L },
                pause = {
                    afterSample()
                    paused.trySend(Unit)
                    releases.receive()
                },
            )
            PerfReader.touchForTest(100L)
            runBlocking {
                repeat(ticks) {
                    withTimeout(5_000L) { paused.receive() }
                    releases.trySend(Unit)
                }
                withTimeout(5_000L) { paused.receive() }
            }
            return PerfReader.json()
        } finally {
            PerfReader.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun dump(): PerfDump = PerfDump(
        stat = listOf(longArrayOf(100, 0, 100, 800)),
        loadavg = emptyList(),
        tempMilli = 0L,
        gpuRaw = null,
        proc = listOf(ProcessMetric(pid = 4242, jiffies = 50L, name = "helper-ranked-proc", rssPages = 2048L)),
        rend = emptyMap(),
    )

    @Test fun anOpenPerfPageRanksProcessesFromTheHelperDump() {
        val fetches = AtomicInteger()
        val json = sampleActively(ticks = 6) { fetches.incrementAndGet(); dump() }

        assertTrue("helper dump must be fetched while the page is open", fetches.get() > 0)
        val topRam = JSONObject(json).optJSONArray("topRam")
        assertNotNull("RAM ranking comes from the helper dump: $json", topRam)
        assertEquals("helper-ranked-proc", topRam!!.getJSONObject(0).getString("name"))
    }

    @Test fun anOpenPerfPageFetchesOneHelperDumpPerTenSecondCycle() {
        val fetches = AtomicInteger()
        // Ticks 0..10 at 2 s each span three 5-tick cycles (ticks 0, 5 and 10).
        sampleActively(ticks = 10) { fetches.incrementAndGet(); dump() }

        assertEquals(3, fetches.get())
    }

    @Test fun withNoHelperTheProcessRankingsAreUnavailable() {
        val json = JSONObject(sampleActively(ticks = 6) { null })

        assertTrue(json.isNull("top"))
        assertTrue(json.isNull("topRam"))
        assertTrue(json.isNull("render"))
    }

    @Test fun helperLossClearsPublishedReadingsAndReturningProcessesStartWithFreshDeltas() {
        val fetches = AtomicInteger()
        val snapshots = mutableListOf<JSONObject>()
        sampleActively(ticks = 21, afterSample = {
            if (fetches.get() > snapshots.size) snapshots += JSONObject(PerfReader.json())
        }) {
            val sample = fetches.incrementAndGet()
            if (sample == 3) null else dump().copy(
                stat = listOf(longArrayOf(sample * 1_000L)),
                proc = listOf(ProcessMetric(pid = 4242, jiffies = sample * 100L,
                    name = "helper-ranked-proc", rssPages = 2048L)),
                rend = mapOf(4242 to sample * 100L),
            )
        }

        assertEquals("one dump for each ten-second cycle", 5, fetches.get())
        assertEquals("helper-ranked-proc", snapshots[1].getJSONArray("top").getJSONObject(0).getString("name"))
        assertNotNull(snapshots[1].optJSONObject("render"))
        for (key in listOf("top", "topRam", "render")) {
            assertTrue("helper loss clears $key", snapshots[2].isNull(key))
        }
        assertTrue("the returning process needs a fresh CPU baseline", snapshots[3].isNull("top"))
        assertEquals("helper-ranked-proc", snapshots[3].getJSONArray("topRam").getJSONObject(0).getString("name"))
        assertEquals("CPU deltas resume from the returning process's own baseline", 10.0,
            snapshots[4].getJSONArray("top").getJSONObject(0).getDouble("cpu"), 0.001)
    }

    @Test fun anIdleSamplerFetchesNoHelperDump() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstPause = CountDownLatch(1)
        val fetches = AtomicInteger()
        try {
            PerfReader.stop()
            PerfReader.startForTest(
                scope = scope,
                perfDump = { fetches.incrementAndGet(); dump() },
                elapsedRealtime = { 100L },
                pause = { firstPause.countDown(); kotlinx.coroutines.awaitCancellation() },
            )
            assertTrue(firstPause.await(2, TimeUnit.SECONDS))
            assertEquals("idle sampler must not fetch a dump", 0, fetches.get())
        } finally {
            PerfReader.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun startIsIdempotentAndStopIsATerminalGenerationBoundary() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            PerfReader.stop()
            PerfReader.start(scope)
            val first = PerfReader.lifecycleGeneration()
            assertTrue(first > 0)

            PerfReader.start(scope)
            assertEquals("a second start must not create another sampler", first, PerfReader.lifecycleGeneration())

            PerfReader.stop()
            assertEquals(0L, PerfReader.lifecycleGeneration())
            assertTrue("\"cpu\":null" in PerfReader.json())
            assertTrue("\"hist\":{\"cpu\":[],\"ram\":[],\"gpu\":[]}" in PerfReader.json())

            PerfReader.start(scope)
            assertTrue("replacement must have a new generation", PerfReader.lifecycleGeneration() > first)
        } finally {
            PerfReader.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
