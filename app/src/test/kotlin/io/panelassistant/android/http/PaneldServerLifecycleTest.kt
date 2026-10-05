package io.panelassistant.android.http

import io.panelassistant.android.util.LatestDispatcher
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerLifecycleTest {
    @Test
    fun prewarmDoesNotAdmitWorkAfterStop() {
        val events = mutableListOf<String>()

        runPrewarmPhases(
            isStopping = { true },
            management = { events += "management" },
            companion = { events += "companion" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun prewarmDoesNotStartCompanionPhaseWhenStopBeginsBetweenPhases() {
        val events = mutableListOf<String>()
        var stopping = false

        runPrewarmPhases(
            isStopping = { stopping },
            management = { events += "management"; stopping = true },
            companion = { events += "companion" },
        )

        assertEquals(listOf("management"), events)
    }

    @Test
    fun startFailureStopsPartialEngineClosesIngressAndPropagatesOriginalFailure() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("bind failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            startOwnedHttpServer(
                start = { events += "start"; throw failure },
                stop = { events += "stop"; error("cleanup failed") },
                closeIngress = { events += "close-ingress" },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("start", "stop", "close-ingress"), events)
    }

    @Test
    fun successfulStartKeepsEngineAndIngressOwnedByCaller() {
        val events = mutableListOf<String>()

        startOwnedHttpServer(
            start = { events += "start" },
            stop = { events += "stop" },
            closeIngress = { events += "close-ingress" },
        )

        assertEquals(listOf("start"), events)
    }

    @Test
    fun stopProofIsStickyAndContinuesAfterAnExceptionAndInterruptIgnoringMutation() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutations = LatestDispatcher<String, Int>("http-stop-proof", 1, consume = { _, _ ->
            entered.countDown()
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model an admitted privileged mutation that does not stop on interruption alone.
                }
            }
        })
        try {
            assertEquals(LatestDispatcher.Admission.ACCEPTED, mutations.submit("pkg", 1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val incomplete = mutableListOf<Pair<String, Throwable?>>()
            val stopped = stopHttpOwners(
                closeOperationAdmission = { events += "close-admission" },
                closeUploadIngress = { events += "close-uploads" },
                stopEngine = { events += "stop-engine"; error("engine stop failed") },
                stopRelay = { events += "stop-relay"; true },
                drainTameMutations = { events += "drain-mutations"; mutations.closeAndJoin(20L) },
                drainRemoteControls = { events += "drain-remote"; true },
                onIncomplete = { owner, error -> incomplete += owner to error },
            )

            assertFalse(stopped)
            assertEquals(
                listOf(
                    "close-admission",
                    "close-uploads",
                    "stop-engine",
                    "stop-relay",
                    "drain-mutations",
                    "drain-remote",
                ),
                events.toList(),
            )
            assertEquals(listOf("HTTP engine stop request", "vendor mutation"), incomplete.map { it.first })
            assertEquals("engine stop failed", incomplete[0].second?.message)
            assertEquals(null, incomplete[1].second)
            assertEquals(LatestDispatcher.Admission.CLOSED, mutations.submit("late", 2))
        } finally {
            release.countDown()
            assertTrue(mutations.closeAndJoin(2_000L))
        }
    }
}
