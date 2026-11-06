package io.panelassistant.android.http

import io.panelassistant.android.RendererTarget
import io.panelassistant.android.metrics.PerfDump
import io.panelassistant.android.metrics.ProcessMetric
import io.panelassistant.android.platform.HelperGfxInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Drive the owned sampler and inspect its public JSON; no direct calls to delta/publication helpers. */
class PerfGfxLifecycleTest {
    private class Sampler(
        target: RendererTarget? = RendererTarget.Builtin,
        gfx: () -> HelperGfxInfo?,
        dumpAvailable: (Int) -> Boolean = { true },
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = executor.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val paused = Channel<Unit>(Channel.UNLIMITED)
        private val releases = Channel<Unit>(Channel.UNLIMITED)
        private val now = AtomicLong(100L)
        val dumps = AtomicInteger()
        init {
            PerfReader.stop()
            PerfReader.startForTest(scope, perfDump = {
                val cycle = dumps.incrementAndGet()
                if (!dumpAvailable(cycle)) null else PerfDump(stat = listOf(longArrayOf(cycle * 1_000L)), loadavg = emptyList(), tempMilli = 0L,
                    gpuRaw = null, proc = listOf(ProcessMetric(4242, cycle * 400L, "renderer", 200L)),
                    rend = mapOf(4242 to cycle * 400L))
            }, elapsedRealtime = { now.get() }, pause = {
                paused.send(Unit); releases.receive()
            }, ownPackage = "io.panelassistant.android", initialTarget = target, gfxInfo = gfx)
            awaitPause() // Establish idle before the page opens.
        }
        fun release(touch: Boolean = true, elapsed: Long = 2_000L) {
            val at = now.addAndGet(elapsed)
            if (touch) PerfReader.touchForTest(at)
            releases.trySend(Unit)
        }
        fun awaitPause() = runBlocking { withTimeout(5_000L) { paused.receive() } }
        fun ticks(count: Int, touch: Boolean = true, elapsed: Long = 2_000L): JSONObject {
            repeat(count) { release(touch, elapsed); awaitPause() }
            return JSONObject(PerfReader.json())
        }
        fun drain() { executor.submit {}.get(5, TimeUnit.SECONDS) }
        fun cycle(first: Boolean = false) = ticks(if (first) 1 else 5)
        override fun close() {
            PerfReader.stop(); scope.cancel(); dispatcher.close(); executor.shutdownNow()
        }
    }

    private fun frame(total: Long, janky: Long, since: String = "7") = HelperGfxInfo(since, total, janky)
    private fun render(json: JSONObject) = json.getJSONObject("render")
    private fun assertCpu(json: JSONObject, expectedHistory: Int? = null) {
        assertTrue("CPU measurement remains available: $json", render(json).has("mainPct"))
        assertEquals(40.0, render(json).getDouble("mainPct"), 0.001)
        assertEquals("renderer", json.getJSONArray("topRam").getJSONObject(0).getString("name"))
        if (expectedHistory != null) assertEquals(expectedHistory, render(json).getJSONArray("hist").length())
    }
    private fun assertNoJank(json: JSONObject) { assertFalse(json.toString(), render(json).has("jankPct")) }

    @Test fun firstCycleSeedsGfxBeforeCpuAndNextCyclePublishesAnIntervalWithoutP99() {
        val calls = AtomicInteger()
        Sampler(gfx = { if (calls.incrementAndGet() == 1) frame(100, 20) else frame(120, 24) }).use { sampler ->
            assertEquals("no-renderer", render(sampler.cycle(first = true)).getString("status"))
            val json = sampler.cycle()
            assertCpu(json, 1)
            assertEquals(20.0, render(json).getDouble("jankPct"), 0.001)
            assertFalse(render(json).has("p99"))
            assertEquals(2, calls.get())
        }
    }

    @Test fun missingThrowingAndReturningGfxNeverResetCpuOrItsHistory() {
        val calls = AtomicInteger()
        Sampler(gfx = {
            when (val sample = calls.incrementAndGet()) {
                3 -> null
                5 -> error("helper unavailable")
                else -> frame(100L + sample * 20L, 20L + sample * 4L)
            }
        }).use { sampler ->
            sampler.cycle(true)
            for (cycle in 2..8) {
                val json = sampler.cycle()
                assertCpu(json, cycle - 1)
                if (cycle in listOf(3, 4, 5, 6)) assertNoJank(json)
                else assertEquals(20.0, render(json).getDouble("jankPct"), 0.001)
            }
            assertEquals(8, calls.get())
        }
    }

    @Test fun gfxSamplingContinuesThroughPerfDumpLossAndCpuReturnsWithItsOwnBaseline() {
        val calls = AtomicInteger()
        Sampler(gfx = { val n = calls.incrementAndGet(); frame(n * 20L, n * 4L) },
            dumpAvailable = { it != 3 }).use { sampler ->
            sampler.cycle(true)
            assertEquals(20.0, render(sampler.cycle()).getDouble("jankPct"), 0.001)
            val lost = sampler.cycle()
            assertTrue(lost.isNull("render")); assertTrue(lost.isNull("topRam"))
            assertEquals("GFX is still acquired when PERFDUMP fails", 3, calls.get())
            val returning = sampler.cycle()
            assertEquals("no-renderer", render(returning).getString("status"))
            assertEquals("renderer", returning.getJSONArray("topRam").getJSONObject(0).getString("name"))
            val recovered = sampler.cycle()
            assertCpu(recovered, 1)
            assertEquals(20.0, render(recovered).getDouble("jankPct"), 0.001)
            assertEquals(5, calls.get())
        }
    }

    @Test fun resetEpochCounterRegressionsAndInvalidIncrementsDoNotInventIntervals() {
        val frames = listOf(frame(100, 20), frame(120, 24), frame(200, 40, "8"),
            frame(220, 44, "8"), frame(100, 20, "8"), frame(120, 24, "8"),
            frame(140, 23, "8"), frame(160, 27, "8"), frame(161, 30, "8"),
            frame(181, 34, "8"), frame(181, 34, "8"))
        val calls = AtomicInteger()
        Sampler(gfx = { frames[calls.getAndIncrement()] }).use { sampler ->
            sampler.cycle(true)
            for (cycle in 2..frames.size) {
                val json = sampler.cycle()
                assertCpu(json, cycle - 1)
                if (cycle in listOf(3, 5, 7, 9, 11)) assertNoJank(json)
                else assertEquals(20.0, render(json).getDouble("jankPct"), 0.001)
            }
        }
    }

    @Test fun idleAndForeignOrMissingTargetsSuppressGfxAndRequireFreshBaseline() {
        val calls = AtomicInteger()
        Sampler(gfx = { val n = calls.incrementAndGet(); frame(n * 20L, n * 4L) }).use { sampler ->
            sampler.cycle(true); assertCpu(sampler.cycle())
            sampler.ticks(1, touch = false, elapsed = 31_000L)
            assertEquals("idle performs no GFX read", 2, calls.get())
            assertNoJank(sampler.cycle())
            assertEquals(20.0, render(sampler.cycle()).getDouble("jankPct"), 0.001)
            for (target in listOf(RendererTarget.Foreign("io.panelassistant.android"), null)) {
                PerfReader.updateRendererTarget(target)
                assertNoJank(sampler.cycle())
            }
            assertEquals(4, calls.get())
            PerfReader.updateRendererTarget(RendererTarget.Builtin)
            assertNoJank(sampler.cycle())
            assertEquals(20.0, render(sampler.cycle()).getDouble("jankPct"), 0.001)
        }
        for (target in listOf(RendererTarget.Foreign("other.app"), null)) {
            val before = calls.get()
            Sampler(target, gfx = { calls.incrementAndGet(); frame(0, 0) }).use { sampler ->
                sampler.cycle(true); assertCpu(sampler.cycle())
            }
            assertEquals(before, calls.get())
        }
    }

    @Test fun blockedBuiltinToForeignToBuiltinReplyCannotPublishOrSeedANewIdentity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val sampler = Sampler(gfx = {
            when (calls.incrementAndGet()) {
                1 -> frame(100, 20)
                2 -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); frame(200, 100) }
                3 -> frame(300, 110)
                else -> frame(320, 114)
            }
        })
        try {
            sampler.cycle(true); sampler.ticks(4); sampler.release()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            PerfReader.updateRendererTarget(RendererTarget.Foreign("other.app"))
            PerfReader.updateRendererTarget(RendererTarget.Builtin)
            release.countDown(); sampler.awaitPause()
            assertEquals("stale result cannot publish", "no-renderer", render(JSONObject(PerfReader.json())).optString("status"))
            assertNoJank(sampler.cycle())
            assertEquals(20.0, render(sampler.cycle()).getDouble("jankPct"), 0.001)
        } finally { release.countDown(); sampler.close() }
    }

    @Test fun stoppedGenerationCannotSeedOrPublishIntoItsReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Sampler(gfx = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); frame(999, 999) })
        try {
            old.release(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val calls = AtomicInteger()
            Sampler(gfx = { val n = calls.incrementAndGet(); frame(n * 20L, n * 4L) }).use { replacement ->
                replacement.cycle(true)
                release.countDown(); old.drain()
                val json = replacement.cycle()
                assertCpu(json)
                assertEquals(20.0, render(json).getDouble("jankPct"), 0.001)
                assertEquals(2, calls.get())
            }
        } finally { release.countDown(); old.close() }
    }
}
