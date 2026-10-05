package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationRegistrationLifecycleTest {
    private data class Pending(
        val delayMs: Long,
        val task: () -> Unit,
        var cancelled: Boolean = false,
    )

    private class FakeScheduler {
        val pending = mutableListOf<Pending>()

        fun schedule(delayMs: Long, task: () -> Unit): ActivationRetryCancellation {
            val entry = Pending(delayMs, task)
            pending += entry
            return ActivationRetryCancellation { entry.cancelled = true }
        }

        fun runNext() {
            pending.removeAt(0).also { if (!it.cancelled) it.task() }
        }
    }

    @Test fun registrationFalseRetriesWithBoundedDelayThenSucceeds() {
        val scheduler = FakeScheduler()
        val results = mutableListOf(false, false, true)
        var registrations = 0
        val states = mutableListOf<ActivationRegistrationState>()
        val lifecycle = ActivationRegistrationLifecycle(
            register = { registrations++; results.removeAt(0) },
            schedule = scheduler::schedule,
            onState = states::add,
        )

        lifecycle.start()
        assertEquals(ActivationRegistrationState.RETRYING, lifecycle.state)
        assertEquals(ACTIVATION_RETRY_INITIAL_MS, scheduler.pending.single().delayMs)
        scheduler.runNext()
        assertEquals(ACTIVATION_RETRY_INITIAL_MS * 2L, scheduler.pending.single().delayMs)
        scheduler.runNext()

        assertEquals(3, registrations)
        assertEquals(ActivationRegistrationState.REGISTERED, lifecycle.state)
        assertEquals(
            listOf(
                ActivationRegistrationState.RETRYING,
                ActivationRegistrationState.RETRYING,
                ActivationRegistrationState.REGISTERED,
            ),
            states,
        )
        assertEquals(ACTIVATION_RETRY_MAX_MS, activationRetryDelayMs(30))
    }

    @Test fun stopCancelsTheOwnedRetryAndPreventsAnotherRegistration() {
        val scheduler = FakeScheduler()
        var registrations = 0
        val lifecycle = ActivationRegistrationLifecycle(
            register = { registrations++; false },
            schedule = scheduler::schedule,
        )

        lifecycle.start()
        val pending = scheduler.pending.single()
        lifecycle.stop()
        assertTrue(pending.cancelled)
        scheduler.runNext()

        assertEquals(1, registrations)
        assertEquals(ActivationRegistrationState.STOPPED, lifecycle.state)
    }

    @Test fun restartCreatesAFreshGenerationAndStaleWorkCannotRegister() {
        val scheduler = FakeScheduler()
        var succeed = false
        var registrations = 0
        val lifecycle = ActivationRegistrationLifecycle(
            register = { registrations++; succeed },
            schedule = scheduler::schedule,
        )

        lifecycle.start()
        val stale = scheduler.pending.single()
        lifecycle.stop()
        succeed = true
        lifecycle.start()

        assertTrue(stale.cancelled)
        assertEquals(2, registrations)
        assertEquals(ActivationRegistrationState.REGISTERED, lifecycle.state)
        stale.task()
        assertEquals("stale generation cannot register", 2, registrations)
        assertFalse("successful restart owns no retry", scheduler.pending.any { !it.cancelled })
    }
}
