package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `the probe inventory and the shell tail are kept and capped for the diag dump`() {
        val diagnostics = BundledHelperInstallDiagnostics(wallClock = { 0L }, monotonic = { 0L })
        assertEquals(emptyList<String>(), diagnostics.detailLines())
        diagnostics.recordProbe((1..60).map { "INV line$it " + "x".repeat(400) })
        diagnostics.recordShell("first\nCUSTODY_REASON system_removal_refused\n" + "y".repeat(5000))
        val lines = diagnostics.detailLines()
        val probe = lines.filter { it.startsWith("[helper install probe] ") }
        assertEquals(40, probe.size)
        assertTrue(probe.all { it.length <= "[helper install probe] ".length + 240 })
        val shell = lines.single { it.startsWith("[helper install shell] ") }
        assertTrue(shell.length <= "[helper install shell] ".length + 2000)
        assertFalse(shell.contains('\n'))
    }

    @Test fun `the migration shell's reason is its last custody reason, else the tail of its output`() {
        assertEquals("system_removal_refused",
            custodyShellReason(1, "+12 [ x ]\nCUSTODY_REASON system_mount_unknown\nCUSTODY_REASON system_removal_refused\n"))
        assertEquals("exit_1:+30_exit_1", custodyShellReason(1, "+29 [ a = b ]\n+30 exit 1\n"))
        assertEquals("no_reply", custodyShellReason(null, null))
    }
}
