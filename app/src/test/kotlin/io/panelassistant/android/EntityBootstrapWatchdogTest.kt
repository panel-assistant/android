package io.panelassistant.android

import io.panelassistant.android.dashboard.EntityBootstrapProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entity-bootstrap watchdog as the reporter of Issue #133 experienced it, and as it must behave now.
 *
 * Every scenario is a sequence of one-second polls against an injected clock. A resync is the expensive
 * action (full catalogue fetch and SQLite rewrite); a probe is one authenticated read; the counts are
 * what the assertions are about.
 */
class EntityBootstrapWatchdogTest {
    private companion object {
        const val RETRY_MS = 30_000L
        const val RETRY_CEILING_MS = 300_000L
        const val PROBLEM_MS = 90_000L
        const val PROBE_MS = 300_000L
        const val PROBE_CEILING_MS = 3_600_000L
        const val SECOND = 1_000L
        const val HOUR = 3_600L * SECOND
    }

    private fun watchdog() = EntityBootstrapWatchdog(RETRY_MS, RETRY_CEILING_MS, PROBLEM_MS, PROBE_MS, PROBE_CEILING_MS)

    private class Run(val watchdog: EntityBootstrapWatchdog) {
        var nowMs = 0L
        val actions = mutableListOf<Pair<Long, EntityBootstrapWatchdogAction>>()

        /** Poll once per second for [durationMs] with a fixed hold state. */
        fun poll(
            durationMs: Long,
            blockingIssues: Int,
            problem: EntityBootstrapProblem? = null,
            syncRunning: Boolean = false,
        ) {
            val end = nowMs + durationMs
            while (nowMs < end) {
                nowMs += SECOND
                val action = watchdog.tick(nowMs, blockingIssues, problem, syncRunning)
                if (action != EntityBootstrapWatchdogAction.IDLE) actions += nowMs to action
            }
        }

        fun count(action: EntityBootstrapWatchdogAction) = actions.count { it.second == action }
    }

    @Test fun `a decision hold never resyncs and never gives up across the reported outage`() {
        // The reported hold: 237,616,126 ms behind an unanswered unbounded-selector decision.
        val run = Run(watchdog())
        run.poll(237_616 * SECOND, blockingIssues = 1)

        assertEquals(0, run.count(EntityBootstrapWatchdogAction.RESYNC))
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))
        assertFalse(run.watchdog.gaveUp)
        assertTrue(run.watchdog.heldOnDecision)
        // Probes: 5, 15, 35, 75, 135 minutes, then hourly. 66 hours holds 68 of them; the same span
        // held 794 full resyncs before.
        assertEquals(68, run.count(EntityBootstrapWatchdogAction.PROBE))
        assertEquals(68, run.watchdog.probes)
        val first = run.actions.first()
        assertEquals(EntityBootstrapWatchdogAction.PROBE, first.second)
        assertEquals(PROBE_MS + SECOND, first.first)
    }

    @Test fun `repeated unchanged polls inside a probe interval do nothing`() {
        val run = Run(watchdog())
        run.poll(PROBE_MS - SECOND, blockingIssues = 3)
        assertTrue(run.actions.isEmpty())
        assertEquals(0, run.watchdog.probes)
    }

    @Test fun `late approval starts no watchdog resync on top of the approval own scan`() {
        val run = Run(watchdog())
        run.poll(2 * 24 * HOUR, blockingIssues = 1)
        val probesBeforeApproval = run.watchdog.probes
        run.actions.clear()

        // Approval: the store rewrites the issue as ignored, so the blocking count drops at once while
        // the scan the approval started is still running.
        run.poll(20 * SECOND, blockingIssues = 0, syncRunning = true)
        assertTrue(run.actions.isEmpty())
        assertFalse(run.watchdog.heldOnDecision)
        assertEquals(0, run.watchdog.probes)

        // The scan finished; the relaunch that ends the hold is a few polls away. The rebased clock
        // means the ladder is nowhere near its first rung.
        run.poll(5 * SECOND, blockingIssues = 0, syncRunning = false)
        assertTrue(run.actions.isEmpty())
        assertEquals(0, run.watchdog.resyncs)
        assertTrue(probesBeforeApproval > 0)
    }

    @Test fun `approval whose blocking count only clears at commit still never resyncs`() {
        val run = Run(watchdog())
        run.poll(6 * HOUR, blockingIssues = 2)
        run.actions.clear()
        run.poll(90 * SECOND, blockingIssues = 2, syncRunning = true)
        assertTrue(run.actions.isEmpty())
        assertEquals(0, run.watchdog.resyncs)
    }

    @Test fun `process restart into a decision hold resyncs nothing`() {
        // A fresh process: the startup scan (Auto scope verification) is running, then it answers with
        // the same blocking rule. Neither phase earns a resync.
        val run = Run(watchdog())
        run.poll(40 * SECOND, blockingIssues = 1, syncRunning = true)
        run.poll(10 * 60 * SECOND, blockingIssues = 1, syncRunning = false)
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.RESYNC))
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))
        // One probe at five minutes; the next is not due until fifteen.
        assertEquals(1, run.count(EntityBootstrapWatchdogAction.PROBE))
    }

    @Test fun `a home assistant outage at boot converges on the widening resync ladder`() {
        val run = Run(watchdog())
        // Nothing has answered: no blocking rule, no recorded problem, no scan running (it died).
        run.poll(HOUR, blockingIssues = 0)
        // Rungs: 30 s, 60 s, 120 s, 240 s, then the 300 s ceiling. The hold started on the first poll at
        // one second, and a rung fires once the held time EXCEEDS it.
        val resyncTimes = run.actions.filter { it.second == EntityBootstrapWatchdogAction.RESYNC }.map { it.first }
        assertEquals(listOf(32L, 62L, 122L, 242L, 482L).map { it * SECOND }, resyncTimes.take(5))
        // Then every 300 s from 782 s: ten more inside the hour.
        assertEquals(15, resyncTimes.size)
        assertEquals(1, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))
        assertTrue(run.watchdog.gaveUp)
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.PROBE))

        // A failed scan records a synchronization problem; the ladder keeps its place.
        run.actions.clear()
        run.poll(10 * 60 * SECOND, blockingIssues = 0, problem = EntityBootstrapProblem.SYNCHRONIZATION)
        assertEquals(2, run.count(EntityBootstrapWatchdogAction.RESYNC))
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))

        // Home Assistant returns and the scan succeeds: the hold ends and the watchdog forgets.
        run.watchdog.reset()
        assertEquals(0, run.watchdog.resyncs)
        assertFalse(run.watchdog.gaveUp)
    }

    @Test fun `a scan already running is never doubled by the ladder`() {
        val run = Run(watchdog())
        run.poll(5 * 60 * SECOND, blockingIssues = 0, syncRunning = true)
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.RESYNC))
        // The give-up honesty rung still fires: a scan that runs for minutes is a problem to show.
        assertEquals(1, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))
    }

    @Test fun `a probe never runs beside a scan and fires as soon as the scan is gone`() {
        val run = Run(watchdog())
        // A scan the approval or a settings change started is still running while the decision stands.
        run.poll(10 * 60 * SECOND, blockingIssues = 1, syncRunning = true)
        assertTrue(run.actions.isEmpty())
        assertEquals(0, run.watchdog.probes)
        // The scan is gone and the probe was already due.
        run.poll(SECOND, blockingIssues = 1, syncRunning = false)
        assertEquals(listOf(EntityBootstrapWatchdogAction.PROBE), run.actions.map { it.second })
    }

    @Test fun `a changed dashboard found by a probe leads to one scan and a clean handover`() {
        val run = Run(watchdog())
        run.poll(PROBE_MS + SECOND, blockingIssues = 1)
        assertEquals(1, run.count(EntityBootstrapWatchdogAction.PROBE))
        run.actions.clear()
        // The probe found a change and started a scan.
        run.poll(30 * SECOND, blockingIssues = 1, syncRunning = true)
        assertTrue(run.actions.isEmpty())
        // The scan answered without a blocking rule; the renderer is about to relaunch.
        run.poll(10 * SECOND, blockingIssues = 0, syncRunning = false)
        assertTrue(run.actions.isEmpty())
        assertFalse(run.watchdog.heldOnDecision)
    }

    @Test fun `a credential fault surfaced during a decision hold hands back to the resync ladder`() {
        val run = Run(watchdog())
        run.poll(HOUR, blockingIssues = 1)
        run.actions.clear()
        run.poll(2 * 60 * SECOND, blockingIssues = 1, problem = EntityBootstrapProblem.AUTHENTICATION)
        assertFalse(run.watchdog.heldOnDecision)
        assertEquals(0, run.count(EntityBootstrapWatchdogAction.PROBE))
        // Rebased at the transition (the first poll after the hour): first rung 30 s later, give-up at 90 s.
        assertEquals(listOf(32L, 62L).map { HOUR + it * SECOND },
            run.actions.filter { it.second == EntityBootstrapWatchdogAction.RESYNC }.map { it.first })
        assertEquals(1, run.count(EntityBootstrapWatchdogAction.PRESENT_PROBLEM))
    }

    @Test fun `a person retry restarts the ladder from the base rung`() {
        val run = Run(watchdog())
        run.poll(HOUR, blockingIssues = 0)
        assertTrue(run.watchdog.gaveUp)
        run.watchdog.restart(run.nowMs)
        assertFalse(run.watchdog.gaveUp)
        assertEquals(0, run.watchdog.resyncs)
        run.actions.clear()
        run.poll(31 * SECOND, blockingIssues = 0)
        assertEquals(listOf(EntityBootstrapWatchdogAction.RESYNC), run.actions.map { it.second })
    }

    @Test fun `the probe cadence widens to the hourly ceiling and stays there`() {
        val run = Run(watchdog())
        run.poll(8 * HOUR, blockingIssues = 1)
        val probeTimes = run.actions.filter { it.second == EntityBootstrapWatchdogAction.PROBE }.map { it.first }
        val gaps = probeTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(10L, 20L, 40L, 60L, 60L).map { it * 60 * SECOND }, gaps.take(5))
        assertTrue(gaps.drop(4).all { it == PROBE_CEILING_MS })
    }

    @Test fun `intervals are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            EntityBootstrapWatchdog(RETRY_CEILING_MS + 1, RETRY_CEILING_MS, PROBLEM_MS, PROBE_MS, PROBE_CEILING_MS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EntityBootstrapWatchdog(RETRY_MS, RETRY_CEILING_MS, PROBLEM_MS, PROBE_CEILING_MS + 1, PROBE_CEILING_MS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EntityBootstrapWatchdog(RETRY_MS, RETRY_CEILING_MS, 0L, PROBE_MS, PROBE_CEILING_MS)
        }
    }
}
