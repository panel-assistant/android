package io.panelassistant.android.control

import io.panelassistant.android.util.MonotonicDeadline
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuFormPolicyTest {
    @Test fun unprobedAndPriorNegativeTryBothDialects() {
        val both = intArrayOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID)

        assertArrayEquals(both, SuFormPolicy.candidates(SuFormPolicy.UNPROBED))
        assertArrayEquals(both, SuFormPolicy.candidates(SuFormPolicy.NONE_LAST_PROBE))
    }

    @Test fun successfulDialectIsTheOnlyCandidate() {
        assertArrayEquals(intArrayOf(SuFormPolicy.TOOLBOX), SuFormPolicy.candidates(SuFormPolicy.TOOLBOX))
        assertArrayEquals(intArrayOf(SuFormPolicy.ANDROID), SuFormPolicy.candidates(SuFormPolicy.ANDROID))
        assertTrue(SuFormPolicy.working(SuFormPolicy.TOOLBOX))
        assertTrue(SuFormPolicy.working(SuFormPolicy.ANDROID))
        assertFalse(SuFormPolicy.working(SuFormPolicy.NONE_LAST_PROBE))
    }

    @Test fun firstDialectCanExhaustItsReservedSliceWithoutStarvingTheSecond() {
        var nowNanos = 0L
        val observed = mutableListOf<Pair<Int, Long>>()
        val deadline = MonotonicDeadline(100L) { nowNanos }

        val selected = SuFormPolicy.firstSuccessfulWithin(SuFormPolicy.UNPROBED, deadline) { form, budget ->
            observed += form to budget.remainingMs()
            if (form == SuFormPolicy.TOOLBOX) {
                nowNanos += TimeUnit.MILLISECONDS.toNanos(budget.remainingMs())
                null
            } else {
                "ok"
            }
        }

        assertEquals(SuFormPolicy.Selection(SuFormPolicy.ANDROID, "ok"), selected)
        assertEquals(
            listOf(SuFormPolicy.TOOLBOX to 50L, SuFormPolicy.ANDROID to 50L),
            observed,
        )
    }

    @Test fun exhaustedFirstDialectPreventsASecondAttempt() {
        var nowNanos = 0L
        val attempted = mutableListOf<Int>()
        val deadline = MonotonicDeadline(100L) { nowNanos }

        val selected = SuFormPolicy.firstSuccessfulWithin<String>(SuFormPolicy.UNPROBED, deadline) { form, _ ->
            attempted += form
            nowNanos += TimeUnit.MILLISECONDS.toNanos(100L)
            null
        }

        assertEquals(null, selected)
        assertEquals(listOf(SuFormPolicy.TOOLBOX), attempted)
    }

    @Test fun zeroBudgetLaunchesNoDialect() {
        var attempts = 0
        val selected = SuFormPolicy.firstSuccessfulWithin<String>(
            SuFormPolicy.UNPROBED,
            MonotonicDeadline(0L) { 0L },
        ) { _, _ ->
            attempts++
            "unexpected"
        }

        assertEquals(null, selected)
        assertEquals(0, attempts)
    }

    // --- firstAccepted (the independent-timeout, sticky-form selection the one-shots share) ----------

    @Test fun firstAcceptedReturnsFirstWorkingDialectAndStopsThere() {
        val tried = mutableListOf<Int>()
        val selected = SuFormPolicy.firstAccepted(SuFormPolicy.UNPROBED) { form ->
            tried += form
            if (form == SuFormPolicy.ANDROID) "ok" else null
        }

        assertEquals(SuFormPolicy.Selection(SuFormPolicy.ANDROID, "ok"), selected)
        assertEquals(listOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID), tried)
    }

    @Test fun firstAcceptedStopsAtTheFirstAcceptedResultWithoutTryingTheSecond() {
        val tried = mutableListOf<Int>()
        val selected = SuFormPolicy.firstAccepted(SuFormPolicy.UNPROBED) { form ->
            tried += form
            "first"
        }

        assertEquals(SuFormPolicy.Selection(SuFormPolicy.TOOLBOX, "first"), selected)
        assertEquals(listOf(SuFormPolicy.TOOLBOX), tried)
    }

    @Test fun firstAcceptedReturnsNullWhenEveryDialectFails() {
        val tried = mutableListOf<Int>()
        val selected = SuFormPolicy.firstAccepted<String>(SuFormPolicy.UNPROBED) { form ->
            tried += form
            null
        }

        assertEquals(null, selected)
        assertEquals(listOf(SuFormPolicy.TOOLBOX, SuFormPolicy.ANDROID), tried)
    }

    @Test fun firstAcceptedWithAStickyFormOnlyTriesThatForm() {
        val tried = mutableListOf<Int>()
        val selected = SuFormPolicy.firstAccepted(SuFormPolicy.ANDROID) { form ->
            tried += form
            "ok"
        }

        assertEquals(SuFormPolicy.Selection(SuFormPolicy.ANDROID, "ok"), selected)
        assertEquals(listOf(SuFormPolicy.ANDROID), tried)
    }
}
