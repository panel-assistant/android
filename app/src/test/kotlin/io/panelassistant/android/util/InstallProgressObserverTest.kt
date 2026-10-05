package io.panelassistant.android.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update entities publish `in_progress` from this observer, so it must fire exactly on each claim
 * and release of the visible lane, see the state it announces, and never run inside the monitor.
 *
 * [InstallProgress] is process-wide, so every ticket a test claims is released before it asserts: a
 * failing assertion must not leave the lane owned and turn every later test into a null dereference.
 */
class InstallProgressObserverTest {
    private val seen = mutableListOf<Pair<Boolean, String>>()
    private val claimed = mutableListOf<InstallProgress.Ticket>()

    private fun observe() {
        InstallProgress.observer = {
            val snapshot = InstallProgress.presentationSnapshot()
            seen += snapshot.running to snapshot.component
        }
    }

    private fun start(component: String): InstallProgress.Ticket? =
        InstallProgress.start(component)?.also { claimed += it }

    @After fun release() {
        InstallProgress.observer = null
        claimed.forEach { InstallProgress.finish(it, "test cleanup") }
    }

    @Test fun startAndSuccessfulFinishEachNotifyOnceWithTheirOwnState() {
        observe()
        val ticket = start("ha-paneld")
        val afterStart = seen.toList()
        ticket?.let { InstallProgress.finish(it, "updating ha-paneld -> 0.9.8") }
        assertNotNull(ticket)
        assertEquals(listOf(true to "ha-paneld"), afterStart)
        assertEquals(listOf(true to "ha-paneld", false to "ha-paneld"), seen)
    }

    @Test fun aFailedInstallReleasesProgressToo() {
        observe()
        val ticket = start("HA Companion")
        ticket?.let { InstallProgress.finish(it, "signer mismatch") }
        assertNotNull(ticket)
        assertEquals(false to "HA Companion", seen.lastOrNull())
        assertFalse(InstallProgress.running)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationReleasesProgressToo() = runTest {
        observe()
        val ticket = start("ha-paneld")
        assertNotNull(ticket)
        val job = launch { error("must not run") }
        InstallProgress.finishOnFailure(ticket!!, job)
        job.cancel()
        testScheduler.runCurrent()
        assertEquals(listOf(true to "ha-paneld", false to "ha-paneld"), seen)
    }

    @Test fun aBusyLaneAndAStaleTicketNotifyNobody() {
        val owner = start("Backup")
        observe()
        val refused = start("ha-paneld")
        val afterRefusal = seen.toList()
        owner?.let { InstallProgress.finish(it, "backup ready") }
        val afterFinish = seen.toList()
        owner?.let { InstallProgress.finish(it, "late duplicate") }
        assertNotNull(owner)
        assertNull(refused)
        assertTrue("a refused claim must not publish progress", afterRefusal.isEmpty())
        assertEquals(listOf(false to "Backup"), afterFinish)
        assertEquals("a stale finish must not notify again", 1, seen.size)
    }

    @Test fun anOrdinaryConfigureClaimAndReleasePublishNoInstallProgress() {
        observe()
        val configure = InstallProgress.startConfigMutation()
        val afterClaim = seen.toList()
        configure?.let(InstallProgress::finishConfigMutation)
        assertNotNull(configure)
        assertTrue(afterClaim.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test fun theObserverRunsOutsideTheMonitor() {
        // Decided inside the observer: a reader that cannot take the monitor while it runs means the
        // observer was called with the monitor held.
        val readerFinished = mutableListOf<Boolean>()
        InstallProgress.observer = {
            val reader = Thread { InstallProgress.presentationSnapshot() }
            reader.start()
            reader.join(2_000L)
            readerFinished += !reader.isAlive
        }
        val ticket = start("ha-paneld")
        ticket?.let { InstallProgress.finish(it, "done") }
        assertNotNull(ticket)
        assertEquals("a reader blocked while the observer held the monitor", listOf(true, true), readerFinished)
    }

    @Test fun aThrowingObserverCannotBreakTheLane() {
        InstallProgress.observer = { error("publisher gone") }
        val ticket = start("ha-paneld")
        ticket?.let { InstallProgress.finish(it, "done") }
        assertNotNull(ticket)
        assertFalse(InstallProgress.running)
    }
}
