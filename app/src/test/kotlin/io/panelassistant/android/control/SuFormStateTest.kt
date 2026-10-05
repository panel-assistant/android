package io.panelassistant.android.control

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuFormStateTest {
    @Test fun startsUnprobedTryingBothDialects() {
        val state = SuFormState()
        assertEquals(SuFormPolicy.UNPROBED, state.current())
        assertFalse(state.working())
        assertArrayEquals(intArrayOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID), state.candidates())
    }

    @Test fun recordSuccessMakesTheDialectStickyAndTheSoleCandidate() {
        val state = SuFormState()
        state.recordSuccess(SuFormPolicy.ANDROID)

        assertEquals(SuFormPolicy.ANDROID, state.current())
        assertTrue(state.working())
        assertArrayEquals(intArrayOf(SuFormPolicy.ANDROID), state.candidates())
    }

    @Test fun recordExhaustionDegradesOnlyAnUnprobedForm() {
        assertEquals(SuFormPolicy.NONE_LAST_PROBE, SuFormState(SuFormPolicy.UNPROBED).also { it.recordExhaustion() }.current())
        // an already-negative probe stays negative
        assertEquals(SuFormPolicy.NONE_LAST_PROBE, SuFormState(SuFormPolicy.NONE_LAST_PROBE).also { it.recordExhaustion() }.current())
    }

    @Test fun recordExhaustionNeverOverwritesAnEstablishedWorkingForm() {
        for (working in intArrayOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID)) {
            val state = SuFormState(working)
            state.recordExhaustion()
            assertEquals(working, state.current())   // a working form is left untouched
        }
    }

    @Test fun aNegativeProbeIsNotSticky_candidatesStillTryBoth() {
        val state = SuFormState(SuFormPolicy.NONE_LAST_PROBE)
        // root-manager readiness can change after boot, so a later boundary re-probes both dialects
        assertArrayEquals(intArrayOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID), state.candidates())
    }

    /**
     * The regression guard for the consolidation: on the unsynchronized one-shot paths a failing call's
     * exhaustion degrade must never clobber a concurrent success. Race the two writes head-to-head many
     * times from a fresh UNPROBED state — the outcome must always be the working form, never the negative
     * probe. (Before the CAS transition this could land on NONE_LAST_PROBE.)
     */
    @Test fun concurrentSuccessIsNeverClobberedByExhaustion() {
        repeat(2_000) {
            val state = SuFormState()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val success = thread(start = false) { start.await(); state.recordSuccess(SuFormPolicy.TOOLBOX); done.countDown() }
            val exhaust = thread(start = false) { start.await(); state.recordExhaustion(); done.countDown() }
            success.start(); exhaust.start()
            start.countDown()
            done.await()

            assertEquals(
                "a proven working dialect must survive a racing exhaustion degrade",
                SuFormPolicy.TOOLBOX,
                state.current(),
            )
        }
    }
}
