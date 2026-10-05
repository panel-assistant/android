package io.panelassistant.android

import io.panelassistant.android.sensors.ProximityReportGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProximityPublicationStateTest {
    private data class RetainedProjection(
        val available: Boolean,
        val near: Boolean?,
        val level: Int?,
    )

    @Test fun independentMasksCannotMutateOrReconcileTheSuppressedChannel() {
        val state = ProximityPublicationState()
        assertEquals(
            ProximityReportGate.BOTH or ProximityPublicationState.AVAILABILITY_CHANGED,
            state.admit(nextNear = false, nextLevel = 10, reportMask = ProximityReportGate.BOTH),
        )

        val presenceOnly = state.admit(
            nextNear = true,
            nextLevel = 90,
            reportMask = ProximityReportGate.PRESENCE,
        )
        assertEquals(ProximityReportGate.PRESENCE, presenceOnly)
        assertEquals(true, state.near)
        assertEquals(10, state.level)
        assertEquals(0, presenceOnly and ProximityReportGate.LEVEL)

        val levelOnly = state.admit(
            nextNear = false,
            nextLevel = 90,
            reportMask = ProximityReportGate.LEVEL,
        )
        assertEquals(ProximityReportGate.LEVEL, levelOnly)
        assertEquals(true, state.near)
        assertEquals(90, state.level)
        assertEquals(0, levelOnly and ProximityReportGate.PRESENCE)
    }

    @Test fun availabilityTransitionOverridesBothChannelsAndTheMask() {
        val state = ProximityPublicationState()
        state.admit(false, 10, ProximityReportGate.BOTH)

        val unavailable = state.admit(null, 90, ProximityReportGate.PRESENCE)
        assertEquals(
            ProximityReportGate.BOTH or ProximityPublicationState.AVAILABILITY_CHANGED,
            unavailable,
        )
        assertEquals(null, state.near)
        assertEquals(null, state.level)
        assertEquals(false, state.available)

        val available = state.admit(true, 70, ProximityReportGate.NONE)
        assertEquals(
            ProximityReportGate.BOTH or ProximityPublicationState.AVAILABILITY_CHANGED,
            available,
        )
        assertEquals(true, state.near)
        assertEquals(70, state.level)
        assertEquals(true, state.available)
    }

    @Test fun staleConnectOfflineDecisionCannotOverwriteConcurrentValidPublication() {
        val state = ProximityPublicationState()
        val retained = AtomicReference(RetainedProjection(false, null, null))
        val connectDecided = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val publicationAttempted = CountDownLatch(1)
        val publicationCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val connect = executor.submit {
                state.serialized {
                    val decision = retainedProjection()
                    connectDecided.countDown()
                    check(releaseConnect.await(2, TimeUnit.SECONDS))
                    retained.set(decision)
                }
            }
            assertTrue(connectDecided.await(2, TimeUnit.SECONDS))

            val publication = executor.submit {
                publicationAttempted.countDown()
                state.serialized {
                    admit(true, 70, ProximityReportGate.BOTH)
                    retained.set(retainedProjection())
                }
                publicationCompleted.countDown()
            }
            assertTrue(publicationAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(publicationCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseConnect.countDown()
            connect.get(2, TimeUnit.SECONDS)
            publication.get(2, TimeUnit.SECONDS)

            assertEquals(RetainedProjection(true, true, 70), retained.get())
            assertEquals(state.serialized { retainedProjection() }, retained.get())
        } finally {
            releaseConnect.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun staleConnectOnlineDecisionCannotOverwriteConcurrentClear() {
        val state = ProximityPublicationState()
        state.admit(false, 10, ProximityReportGate.BOTH)
        val retained = AtomicReference(RetainedProjection(true, false, 10))
        val connectDecided = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val clearAttempted = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val connect = executor.submit {
                state.serialized {
                    val decision = retainedProjection()
                    connectDecided.countDown()
                    check(releaseConnect.await(2, TimeUnit.SECONDS))
                    retained.set(decision)
                }
            }
            assertTrue(connectDecided.await(2, TimeUnit.SECONDS))

            val clear = executor.submit {
                clearAttempted.countDown()
                state.serialized {
                    clear()
                    retained.set(retainedProjection())
                }
                clearCompleted.countDown()
            }
            assertTrue(clearAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(clearCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseConnect.countDown()
            connect.get(2, TimeUnit.SECONDS)
            clear.get(2, TimeUnit.SECONDS)

            assertEquals(RetainedProjection(false, null, null), retained.get())
            assertEquals(state.serialized { retainedProjection() }, retained.get())
        } finally {
            releaseConnect.countDown()
            executor.shutdownNow()
        }
    }

    private fun ProximityPublicationState.retainedProjection(): RetainedProjection {
        val isAvailable = near != null && level != null
        return if (isAvailable) {
            RetainedProjection(true, near, level)
        } else {
            RetainedProjection(false, null, null)
        }
    }
}
