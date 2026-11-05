package io.panelassistant.android.http

import io.panelassistant.android.control.CountingSuLaunch
import io.panelassistant.android.control.DensityController
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.SuRecoveryProof
import io.panelassistant.android.control.SuShell
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.util.BundledHelperInstaller
import io.panelassistant.android.util.currentHelperProvesRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status views answer from observed su outcomes only. These tests drive the start-up check, the
 * service's bounded recovery proof ([SuRecoveryProof.of], as the service wires it) and the status
 * readers together over the real [SuShell], counting every su start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuRecoveryProofTest {
    private val su = CountingSuLaunch()
    private val shell = SuShell(launch = su::launch, nowMs = { 0L })

    private val observations = ManagementObservations(
        object : android.content.ContextWrapper(null) {},
        DensityController(canSu = false),
        { error("not used") },
        { _, _ -> error("not used") },
        CoroutineScope(Dispatchers.Unconfined),
        { false },
        nowMs = { 0L },
        root = shell,
        helperRootProbe = { false },
    )

    private object NoAccessibility : AccessibilityActions {
        override fun back() = false
        override fun recents() = false
        override fun tap(x: Int, y: Int) = false
    }
    private val interactive = InteractiveController(canSu = true, root = shell, daemon = FakeDaemon(available = false), accessibility = NoAccessibility)

    private fun suReady(): Boolean {
        val ready = observations.privilegeObservation().directSuReady
        assertEquals("the two status views agree", ready, interactive.canBack())
        return ready
    }

    /** Status polling between two moments: twenty reads, none of which may start su. */
    private fun pollStatus(expectReady: Boolean) {
        val before = su.attempts.get()
        repeat(20) { assertEquals(expectReady, suReady()) }
        assertEquals("status reads started su", before, su.attempts.get())
    }

    private fun TestScope.startProof() = backgroundScope.launch { SuRecoveryProof.of(shell).run() }

    @After fun close() = su.close()

    @Test fun `a current helper's start-up check proves su for the status views`() {
        assertTrue(currentHelperProvesRoot(BundledHelperInstaller.Result.ALREADY_CURRENT, shell))
        pollStatus(expectReady = true)
    }

    @Test fun `a start-up check that loses the boot race is recovered by the bounded schedule`() = runTest {
        su.mode = CountingSuLaunch.Mode.DEAD // the root manager is not up yet
        assertFalse(currentHelperProvesRoot(BundledHelperInstaller.Result.ALREADY_CURRENT, shell))
        startProof()
        runCurrent()
        val afterStartup = su.attempts.get()
        pollStatus(expectReady = false)

        advanceTimeBy(30_001L) // first proof: root still down
        assertEquals("one bounded proof, both dialects tried", afterStartup + 2, su.attempts.get())
        pollStatus(expectReady = false)

        su.mode = CountingSuLaunch.Mode.WORKING
        advanceTimeBy(90_000L) // second proof at 2 min: root is up
        assertEquals("the second proof starts one su", afterStartup + 3, su.attempts.get())
        pollStatus(expectReady = true)

        advanceTimeBy(3_600_000L)
        assertEquals("the schedule stops at the first success", afterStartup + 3, su.attempts.get())
    }

    @Test fun `a panel without su starts nothing over the whole schedule`() = runTest {
        su.mode = CountingSuLaunch.Mode.ABSENT
        assertFalse(currentHelperProvesRoot(BundledHelperInstaller.Result.ALREADY_CURRENT, shell))
        val afterStartup = su.attempts.get()
        startProof()
        advanceTimeBy(3_600_000L)
        pollStatus(expectReady = false)
        assertEquals(afterStartup, su.attempts.get())
    }

    @Test fun `a recorded loss re-arms the schedule and root recovers without another root caller`() = runTest {
        assertTrue(currentHelperProvesRoot(BundledHelperInstaller.Result.ALREADY_CURRENT, shell))
        startProof()
        advanceTimeBy(700_000L)
        val proven = su.attempts.get()
        assertEquals("no proof runs while su answers", proven, su.attempts.get())

        // su dies; the next real root call finds out and records the loss.
        su.mode = CountingSuLaunch.Mode.DEAD
        su.close()
        assertFalse(shell.run("true"))
        pollStatus(expectReady = false)

        su.mode = CountingSuLaunch.Mode.WORKING
        val beforeRecovery = su.attempts.get()
        advanceTimeBy(30_001L)
        assertEquals("one proof 30 s after the loss", beforeRecovery + 1, su.attempts.get())
        pollStatus(expectReady = true)
        assertTrue("the lost-root gate is cleared for real root calls", shell.run("true"))
    }
}
