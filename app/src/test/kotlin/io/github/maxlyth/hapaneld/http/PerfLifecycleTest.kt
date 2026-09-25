package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.RendererTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
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

    @Test fun rootCapabilityProbeWaitsForAnActivePerfWindow() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstPause = CountDownLatch(1)
        val rootProbed = CountDownLatch(1)
        val releases = Channel<Unit>(Channel.UNLIMITED)
        val rootCalls = AtomicInteger()
        try {
            PerfReader.stop()
            PerfReader.startForTest(
                scope = scope,
                rootAvailable = {
                    rootCalls.incrementAndGet()
                    rootProbed.countDown()
                    true
                },
                elapsedRealtime = { 100L },
                pause = {
                    firstPause.countDown()
                    releases.receive()
                },
            )

            assertTrue(firstPause.await(2, TimeUnit.SECONDS))
            assertEquals("idle sampler must not launch a privileged probe", 0, rootCalls.get())

            PerfReader.touchForTest(100L)
            releases.trySend(Unit)
            assertTrue("active sampling must probe root capability", rootProbed.await(2, TimeUnit.SECONDS))
            assertEquals(1, rootCalls.get())
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
