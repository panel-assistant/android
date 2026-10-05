package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The /proc/self/stat parse behind the perf card's "sampling probes" row: cutime+cstime only — the
 * su/dumpsys/cat probes the sampler spawns are reaped children, so their jiffies land there and
 * nowhere else. The app's own utime/stime stays in its ranked row (with the built-in renderer that's
 * genuine dashboard-hosting work, not overhead).
 */
class PerfSelfStatTest {

    // pid (comm) state ppid pgrp sid tty tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime ...
    private fun stat(comm: String, utime: Long, stime: Long, cutime: Long, cstime: Long) =
        "1234 ($comm) S 1 1234 0 0 -1 1077936448 2500 800 3 0 $utime $stime $cutime $cstime 20 0 30 0 100 900000 500"

    @Test
    fun `sums only reaped-child jiffies`() {
        assertEquals(300L + 60, PerfReader.childJiffiesOf(stat("hapaneld", 100, 40, 300, 60)))
    }

    @Test
    fun `comm with spaces and parens does not break field positions`() {
        // comm is arbitrary (kernel truncates to 16 chars but allows ') ' inside) — parse from the LAST ')'.
        assertEquals(7L + 5, PerfReader.childJiffiesOf(stat("evil) 9 9 9 (x", 10, 2, 7, 5)))
    }

    @Test
    fun `malformed line yields null`() {
        assertNull(PerfReader.childJiffiesOf("garbage"))
        assertNull(PerfReader.childJiffiesOf("1234 (x) S 1"))
    }
}
