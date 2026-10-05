package io.panelassistant.android.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reclamation admission policy: when a bounded freelist pass may run at all.
 *
 * Reclamation costs space before it returns any — relocated pages become WAL frames and the file
 * only shrinks at the checkpoint afterwards — so the interesting cases are the refusals.
 */
class ReclamationAdmissionTest {
    private val pageSize = 4_096L
    private val maxPages = 5_120L
    private val margin = 64L * 1024L * 1024L

    /** One pass's own transient cost plus the margin: the exact bar [reclamationAdmitted] sets. */
    private val required = maxPages * pageSize + margin

    private fun admitted(
        severity: StorageHealthSeverity = StorageHealthSeverity.HEALTHY,
        usableBytes: Long? = required,
        pageSizeBytes: Long = pageSize,
    ) = reclamationAdmitted(severity, usableBytes, pageSizeBytes, maxPages, margin)

    @Test fun ampleHeadroomAdmitsThePass() {
        assertTrue(admitted(usableBytes = required * 4))
    }

    @Test fun exactlyEnoughHeadroomAdmitsThePass() {
        assertTrue("the bar is inclusive; one byte of slack is not required", admitted(usableBytes = required))
    }

    @Test fun oneByteBelowTheBarRefusesThePass() {
        assertFalse(admitted(usableBytes = required - 1L))
    }

    @Test fun aPassIsRefusedWhenItsOwnTransientCostAloneWouldNotFit() {
        // Isolates the transient term: with no margin, the bar IS the slice budget, so this can only
        // pass while that budget is genuinely part of the bar. With the margin left in, the margin
        // alone would refuse and the assertion would say nothing about the budget.
        assertFalse(
            "the slice budget itself must be part of the bar",
            reclamationAdmitted(
                StorageHealthSeverity.HEALTHY,
                usableBytes = maxPages * pageSize - 1L,
                pageSizeBytes = pageSize,
                maxPagesPerPass = maxPages,
                marginBytes = 0L,
            ),
        )
        assertTrue(
            "and exactly that budget must be enough once it is met",
            reclamationAdmitted(
                StorageHealthSeverity.HEALTHY,
                usableBytes = maxPages * pageSize,
                pageSizeBytes = pageSize,
                maxPagesPerPass = maxPages,
                marginBytes = 0L,
            ),
        )
    }

    @Test fun criticalPressureRefusesEvenWithVastHeadroom() {
        // Free bytes are not the only way to be in trouble: WAL growth and a percentage ceiling also
        // raise CRITICAL, and none of them is a good moment to start writing relocation frames.
        assertFalse(admitted(severity = StorageHealthSeverity.CRITICAL, usableBytes = Long.MAX_VALUE))
    }

    @Test fun aLatchedDatabaseFailureRefusesEvenWithVastHeadroom() {
        assertFalse(
            admitted(severity = StorageHealthSeverity.DATABASE_FAILURE, usableBytes = Long.MAX_VALUE),
        )
    }

    @Test fun warningPressureStillAdmitsWhenTheSpaceIsThere() {
        // WARNING is where reclamation is most useful; refusing here would leave the panel to drift
        // into CRITICAL with the freelist it could have returned.
        assertTrue(admitted(severity = StorageHealthSeverity.WARNING, usableBytes = required))
    }

    @Test fun warningPressureWithoutTheSpaceIsStillRefused() {
        assertFalse(admitted(severity = StorageHealthSeverity.WARNING, usableBytes = required - 1L))
    }

    @Test fun anUnavailableCapacityReadingAdmitsThePass() {
        // Deliberate fail-open, and the only one: a probe that could not be taken at all is not
        // evidence the filesystem is full, and the pass is bounded either way.
        assertTrue(admitted(usableBytes = null))
    }

    @Test fun aMeasuredZeroIsAFullFilesystemAndMustRefuse() {
        // A full filesystem reports zero available blocks. Collapsing that into the same value as a
        // missing probe admitted a relocation pass onto a disk with no room for it — the exact
        // outcome this gate exists to prevent, produced by the gate itself.
        assertFalse("zero free bytes is a measurement, not an absent reading", admitted(usableBytes = 0L))
    }

    @Test fun anUnavailableCapacityReadingDoesNotOverrideCriticalPressure() {
        assertFalse(admitted(severity = StorageHealthSeverity.CRITICAL, usableBytes = null))
    }

    @Test fun anUnknownPageSizeStillAdmitsOnAFullFilesystem() {
        // Documenting the one ordering that remains fail-open: with no page size there is no bar to
        // compute, so the pass is admitted and bounded by its slice cap alone.
        assertTrue(admitted(usableBytes = 0L, pageSizeBytes = 0L))
    }

    @Test fun anUnknownPageSizeAdmitsThePassRatherThanComputingANonsenseBar() {
        assertTrue(admitted(pageSizeBytes = 0L))
        assertTrue(admitted(pageSizeBytes = -1L))
    }

    @Test fun anAbsurdPageSizeCannotOverflowIntoAdmission() {
        // A saturating computation, not a wrapping one: a bar that overflowed to a negative number
        // would admit every pass precisely when the arithmetic said it should refuse.
        assertFalse(
            reclamationAdmitted(
                StorageHealthSeverity.HEALTHY,
                usableBytes = Long.MAX_VALUE - 1L,
                pageSizeBytes = Long.MAX_VALUE,
                maxPagesPerPass = maxPages,
                marginBytes = margin,
            ),
        )
    }

    @Test fun uncheckedAndHealthyBothAdmitWhenTheSpaceIsThere() {
        assertTrue(admitted(severity = StorageHealthSeverity.UNCHECKED))
        assertTrue(admitted(severity = StorageHealthSeverity.HEALTHY))
    }
}
