package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbSuccessorHandoffTest {
    @Test fun `successor alarm identity is fixed`() {
        assertEquals("io.github.maxlyth.hapaneld.action.GUARD_DB_SUCCESSOR_RETRY", GuardDbSuccessorAlarm.ACTION)
        assertEquals(0x48414752, GuardDbSuccessorAlarm.REQUEST_CODE)
        assertEquals(1_000L, GuardDbSuccessorAlarm.DELAY_MS)
    }

    @Test fun `accepted alarm publication precedes process exit`() {
        val events = mutableListOf<String>()
        var localRetryScheduled = false
        GuardDbSuccessorHandoff(
            publishAlarmRetry = { events += "publish-alarm" },
            exitCurrentProcess = { events += "exit" },
            scheduleAlarmPublicationRetry = { _, _ -> localRetryScheduled = true },
            onPublicationFailure = { throw AssertionError("unexpected publication failure", it) },
        ).request()

        assertEquals(listOf("publish-alarm", "exit"), events)
        assertFalse(localRetryScheduled)
    }

    @Test fun `failed alarm publication retains process and retries publication without direct start`() {
        val publicationFailure = IllegalStateException("alarm service unavailable")
        val events = mutableListOf<String>()
        var observedFailure: Throwable? = null
        var publicationAllowed = false
        var retryDelayMs: Long? = null
        var retry: (() -> Unit)? = null
        val handoff = GuardDbSuccessorHandoff(
            publishAlarmRetry = {
                events += "publish-alarm"
                if (!publicationAllowed) throw publicationFailure
            },
            exitCurrentProcess = { events += "exit" },
            scheduleAlarmPublicationRetry = { delayMs, action ->
                retryDelayMs = delayMs
                retry = action
            },
            onPublicationFailure = { observedFailure = it },
        )

        handoff.request()

        assertEquals(listOf("publish-alarm"), events)
        assertSame(publicationFailure, observedFailure)
        assertEquals(GuardDbSuccessorHandoff.RETRY_DELAY_MS, retryDelayMs)
        assertTrue("writer-free process must remain live while no OS retry exists", "exit" !in events)

        publicationAllowed = true
        val scheduledRetry = retry
        assertTrue("alarm publication failure must retain a scheduling retry", scheduledRetry != null)
        scheduledRetry!!.invoke()

        assertEquals(listOf("publish-alarm", "publish-alarm", "exit"), events)
    }
}
