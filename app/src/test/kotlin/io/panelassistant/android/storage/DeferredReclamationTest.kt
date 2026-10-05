package io.panelassistant.android.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovering bytes a reader-blocked checkpoint could not hand back.
 *
 * A reader whose snapshot predates a drain pins the WAL backfill, so that pass frees pages and the
 * file does not shrink. By then the freelist is already back at its retention floor, so a rule that
 * looked only at the freelist would never bring a pass back to the checkpoint, and the bytes would
 * sit in the WAL until the freelist happened to rebuild. These two decisions are what close that.
 */
class DeferredReclamationTest {
    private val retained = 512L

    private fun wanted(freelist: Long, pending: Boolean) =
        reclamationPassWanted(freelist, retained, pending)

    @Test fun afreelistAboveTheFloorAlwaysWantsAPass() {
        assertTrue(wanted(freelist = retained + 1L, pending = false))
        assertTrue(wanted(freelist = 30_000L, pending = false))
    }

    @Test fun anIdlePanelWithNothingStrandedWantsNoPass() {
        assertFalse(wanted(freelist = retained, pending = false))
        assertFalse(wanted(freelist = 0L, pending = false))
    }

    @Test fun aDrainedFreelistStillWantsAPassWhileBytesAreStranded() {
        // The whole point: at or below the floor, only the pending flag can bring the checkpoint
        // back. Without it the deferred bytes are unreachable.
        assertTrue(wanted(freelist = retained, pending = true))
        assertTrue(wanted(freelist = 0L, pending = true))
    }

    @Test fun aReaderBlockedCheckpointLeavesTheBytesPending() {
        assertTrue(
            "pages were freed and no bytes came back, so they are still in the WAL",
            walReclamationStillPending(freedPages = 1_800L, bytesReturned = 0L, wasPending = false),
        )
    }

    @Test fun aCheckpointThatReturnsBytesClearsPending() {
        assertFalse(walReclamationStillPending(freedPages = 1_800L, bytesReturned = 1L, wasPending = false))
        assertFalse(
            "a retry that succeeds must clear the flag, or every later pass checkpoints for ever",
            walReclamationStillPending(freedPages = 0L, bytesReturned = 7_340_032L, wasPending = true),
        )
    }

    @Test fun aRetryThatIsStillBlockedStaysPending() {
        assertTrue(walReclamationStillPending(freedPages = 0L, bytesReturned = 0L, wasPending = true))
    }

    @Test fun anIdlePassCannotInventPendingWork() {
        // Freed nothing, returned nothing, nothing was owed: an idle panel must not latch itself
        // into checkpointing on every maintenance pass for the life of the process.
        assertFalse(walReclamationStillPending(freedPages = 0L, bytesReturned = 0L, wasPending = false))
    }

    @Test fun theTwoDecisionsCloseTheLoopAcrossPasses() {
        // Pass 1: a real drain, reader-blocked. Pass 2: freelist back at the floor, retry admitted,
        // checkpoint succeeds. Pass 3: nothing owed, no pass.
        var pending = false
        assertTrue(wanted(freelist = 2_354L, pending = pending))
        pending = walReclamationStillPending(freedPages = 1_842L, bytesReturned = 0L, wasPending = pending)
        assertTrue("the blocked pass must leave the bytes owed", pending)

        assertTrue("and must bring the next pass back to the checkpoint", wanted(freelist = retained, pending = pending))
        pending = walReclamationStillPending(freedPages = 0L, bytesReturned = 7_544_832L, wasPending = pending)
        assertFalse(pending)

        assertFalse(wanted(freelist = retained, pending = pending))
    }
}
