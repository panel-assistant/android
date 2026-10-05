package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRestartCoordinatorTest {
    @Test
    fun `process restart is single flight and waits for response grace`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val events = mutableListOf<String>()
        val coordinator = ProfileRestartCoordinator(
            schedule = { delay, action -> scheduled += delay to action; true },
            restartProcess = { events += "restart-process" },
        )

        assertTrue(coordinator.request())
        assertFalse(coordinator.request())
        assertEquals(listOf(ProfileRestartCoordinator.RESPONSE_GRACE_MS), scheduled.map { it.first })

        scheduled.removeAt(0).second()
        assertEquals(listOf("restart-process"), events)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `restart waits until destructive lane becomes safe`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var safe = false
        var restarted = false
        val coordinator = ProfileRestartCoordinator(
            schedule = { delay, action -> scheduled += delay to action; true },
            restartProcess = { restarted = true },
            safeToRestart = { safe },
        )

        assertTrue(coordinator.request())
        scheduled.removeAt(0).second()
        assertFalse(restarted)
        assertEquals(ProfileRestartCoordinator.BUSY_RETRY_MS, scheduled.single().first)

        safe = true
        scheduled.removeAt(0).second()
        assertTrue(restarted)
    }

    @Test
    fun `scheduler rejection does not consume single flight admission`() {
        var accepts = false
        val coordinator = ProfileRestartCoordinator(
            schedule = { _, _ -> accepts },
            restartProcess = {},
        )

        assertFalse(coordinator.request())
        accepts = true
        assertTrue(coordinator.request())
    }

    @Test
    fun `terminal owner teardown abandons a delayed restart`() {
        val scheduled = mutableListOf<() -> Unit>()
        var stopping = false
        var restarted = false
        val coordinator = ProfileRestartCoordinator(
            schedule = { _, action -> scheduled += action; true },
            restartProcess = { restarted = true },
            shouldAbandon = { stopping },
        )

        assertTrue(coordinator.request())
        stopping = true
        scheduled.removeAt(0).invoke()
        assertFalse(restarted)
        stopping = false
        assertTrue("abandoned admission can be requested by a later healthy owner", coordinator.request())
    }
}
