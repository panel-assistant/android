package io.panelassistant.android.control

import io.panelassistant.android.platform.AccessibilityActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status reads that ask "is root usable" on every reconcile or live transport wake must never start su
 * themselves: they report what real root actions already observed. Driven through the controllers over
 * the real [SuShell], with only process launch and the clock replaced.
 */
class PassiveRootReadsTest {
    private var now = 0L
    private val su = CountingSuLaunch()
    private val shell = SuShell(launch = su::launch, nowMs = { now })

    private object NoAccessibility : AccessibilityActions {
        override fun back() = false
        override fun recents() = false
        override fun tap(x: Int, y: Int) = false
    }

    private val interactive = InteractiveController(canSu = true, root = shell, daemon = FakeDaemon(available = false), accessibility = NoAccessibility)
    private fun system() = SystemController(FakeSystemEnv(), shell, FakeDaemon(available = false))

    @After fun close() = su.close()

    @Test fun `back availability starts no root work once a root action has proved su`() {
        // A rare root action (an install step) proves su with one one-shot; no persistent shell is open.
        assertEquals("", shell.runOutputIsolatedBounded("true", 1L, 5_000L))
        val before = su.rootWork()

        repeat(20) { assertTrue("root back is offered", interactive.canBack()); now += 61_000L }

        assertEquals("20 reconciles started root work", 0, su.rootWork() - before)
    }

    @Test fun `back availability starts nothing and offers no root route before su is proved`() {
        repeat(20) { assertFalse(interactive.canBack()); now += 61_000L }
        assertEquals(0, su.rootWork())
    }

    @Test fun `reboot availability starts no root work and follows the last real su outcome`() {
        assertEquals("", shell.runOutputIsolatedBounded("true", 1L, 5_000L))
        val before = su.rootWork()
        assertTrue(system().canReboot())
        assertEquals("the availability read started root work", 0, su.rootWork() - before)

        // su stops answering; the next real root call finds out, and later reads say so without asking.
        su.mode = CountingSuLaunch.Mode.DEAD
        now += 61_000L
        assertFalse(shell.run("true"))
        val afterLoss = su.rootWork()
        repeat(5) { assertFalse(system().canReboot()); now += 61_000L }
        assertEquals(afterLoss, su.rootWork())

        su.mode = CountingSuLaunch.Mode.WORKING
        assertEquals("", shell.runOutputIsolatedBounded("true", 1L, 5_000L))
        assertTrue("a later real success restores the view", system().canReboot())
    }

    @Test fun `a panel without su is reported unusable without another launch`() {
        su.mode = CountingSuLaunch.Mode.ABSENT
        assertFalse(shell.available())
        repeat(5) { assertFalse(interactive.canBack()) }
        assertFalse(shell.observedUsable())
    }
}
