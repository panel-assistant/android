package io.panelassistant.android.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [BuiltinDashboard] is a process-global object; reset the navbar-handoff state before AND after each
 *  test so it can't leak into (or out of) other test classes sharing the JVM. */
class BuiltinDashboardNavbarTest {
    private var owner = 0L

    @Before fun reset() {
        BuiltinDashboard.setForegroundListener(null)
        BuiltinDashboard.setNavbarRevealHandler(null)
        BuiltinDashboard.setScreenListener(null)
        BuiltinDashboard.onScreenAwake(true)
        owner = BuiltinDashboard.acquireActivityOwner()
        BuiltinDashboard.setActivityForeground(owner, false)
    }

    @After fun cleanUp() {
        BuiltinDashboard.releaseActivityOwner(owner)
        BuiltinDashboard.setForegroundListener(null)
        BuiltinDashboard.setNavbarRevealHandler(null)
        BuiltinDashboard.setScreenListener(null)
    }

    @Test fun foregroundListenerFiresOnChangeOnly() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.setActivityForeground(owner, true) // change → fire
        BuiltinDashboard.setActivityForeground(owner, true) // no change → silent (redundant onResume+onTopResumed)
        BuiltinDashboard.setActivityForeground(owner, false) // change → fire
        assertEquals(listOf(true, false), events)
    }

    @Test fun foregroundSetToSameValueIsSilent() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.setActivityForeground(owner, false) // already false from reset → no change
        assertTrue(events.isEmpty())
    }

    @Test fun clearedForegroundListenerGetsNoCallbacks() {
        val events = mutableListOf<Boolean>()
        BuiltinDashboard.setForegroundListener { events.add(it) }
        BuiltinDashboard.setActivityForeground(owner, true)
        BuiltinDashboard.setForegroundListener(null)
        BuiltinDashboard.setActivityForeground(owner, false) // no listener → nothing recorded
        assertEquals(listOf(true), events)
    }

    @Test fun revealHandlerSlotSemantics() {
        assertFalse("no handler → not enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal() // no handler → no-op, must not throw

        var reveals = 0
        BuiltinDashboard.setNavbarRevealHandler { reveals++ }
        assertTrue("handler set → enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal()
        BuiltinDashboard.requestNavbarReveal()
        assertEquals(2, reveals)

        BuiltinDashboard.setNavbarRevealHandler(null)
        assertFalse("handler cleared → not enabled", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal() // cleared → no-op
        assertEquals(2, reveals)
    }

    @Test fun staleActivityCannotOverwriteReplacementForegroundOrAuthState() {
        BuiltinDashboard.setActivityForeground(owner, true)
        BuiltinDashboard.setActivityAuthLatched(owner, true)
        val replacement = BuiltinDashboard.acquireActivityOwner()
        BuiltinDashboard.setActivityForeground(replacement, true)
        BuiltinDashboard.setActivityAuthLatched(replacement, true)

        BuiltinDashboard.setActivityForeground(owner, false)
        BuiltinDashboard.setActivityAuthLatched(owner, false)
        BuiltinDashboard.releaseActivityOwner(owner)

        assertTrue(BuiltinDashboard.foreground)
        assertTrue(BuiltinDashboard.authLatched)
        owner = replacement
    }

    @Test fun staleActivityCannotClearTheReplacementScreenListener() {
        val oldEvents = mutableListOf<Boolean>()
        val replacementEvents = mutableListOf<Boolean>()
        val oldListener: (Boolean) -> Unit = oldEvents::add
        val replacementListener: (Boolean) -> Unit = replacementEvents::add
        BuiltinDashboard.setScreenListener(oldListener)
        BuiltinDashboard.setScreenListener(replacementListener)

        BuiltinDashboard.clearScreenListener(oldListener)
        BuiltinDashboard.onScreenAwake(false)
        BuiltinDashboard.clearScreenListener(replacementListener)
        BuiltinDashboard.onScreenAwake(true)

        assertTrue(oldEvents.isEmpty())
        assertEquals(listOf(false), replacementEvents)
    }
}
