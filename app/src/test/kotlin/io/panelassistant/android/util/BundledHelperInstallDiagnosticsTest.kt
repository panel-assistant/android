package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every start's helper install outcome is one diag line: what it returned, why, when and for how long. */
class BundledHelperInstallDiagnosticsTest {
    @Test fun `the diag line reports no run, a run in progress and the last outcome with its reason`() {
        val diagnostics = BundledHelperInstallDiagnostics(wallClock = { 1_000L }, monotonic = { 50L })
        assertEquals("never_run running=no", diagnostics.summary())
        diagnostics.began()
        assertEquals("never_run running=yes", diagnostics.summary())
        var now = 50L
        val timed = BundledHelperInstallDiagnostics(wallClock = { 1_762_700_000_000L }, monotonic = { now })
        timed.began()
        now = 1_250L
        timed.finished(BundledHelperInstaller.Result.SKIPPED, "root_not_observed")
        assertEquals(
            "last=SKIPPED reason=root_not_observed at=2025-11-09T14:53:20Z elapsed_ms=1200 running=no",
            timed.summary(),
        )
        timed.began()
        assertEquals(
            "last=SKIPPED reason=root_not_observed at=2025-11-09T14:53:20Z elapsed_ms=1200 running=yes",
            timed.summary(),
        )
    }

    @Test fun `a reason is never blank or spaced so the line stays one parseable record`() {
        val diagnostics = BundledHelperInstallDiagnostics(wallClock = { 0L }, monotonic = { 0L })
        diagnostics.began()
        diagnostics.finished(BundledHelperInstaller.Result.FAILED, "exception: java.io IOException")
        assertEquals(
            "last=FAILED reason=exception:_java.io_IOException at=1970-01-01T00:00:00Z elapsed_ms=0 running=no",
            diagnostics.summary(),
        )
        diagnostics.began()
        diagnostics.finished(BundledHelperInstaller.Result.ALREADY_CURRENT, null)
        assertEquals(
            "last=ALREADY_CURRENT reason=unrecorded at=1970-01-01T00:00:00Z elapsed_ms=0 running=no",
            diagnostics.summary(),
        )
    }
}
