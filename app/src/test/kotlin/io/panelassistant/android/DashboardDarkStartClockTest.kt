package io.panelassistant.android

import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An activity started with the screen dark runs no status clock: the cover's count-up and the network
 * wait's once-a-second tick neither paint nor repost until the screen wakes. Built through the activity's
 * own factories, so deleting their screen wiring fails here.
 */
class DashboardDarkStartClockTest {
    @Test
    fun darkStartedCoverClockDoesNotTick() = DashboardRendererRig().use { rig ->
        rig.setPaced("screenAwake", false)
        rig.cover.countUp(DashboardRendererRig.allocate(TextView::class.java), 0L)
        assertTrue("a dark cover posted its clock tick", rig.posted.isEmpty())

        rig.setPaced("screenAwake", true)
        rig.cover.countUp(DashboardRendererRig.allocate(TextView::class.java), 0L)
        assertEquals("a lit cover counts up", 1, rig.posted.size)
    }

    @Test
    fun darkStartedNetworkWaitClockDoesNotTick() = DashboardRendererRig().use { rig ->
        rig.setPaced("screenAwake", false)
        val screens = rig.statusScreens
        DashboardStatusScreens::class.java.getDeclaredField("waitingStatus").apply { isAccessible = true }
            .set(screens, DashboardRendererRig.allocate(TextView::class.java))
        val tick = DashboardStatusScreens::class.java.getDeclaredField("waitingTick").apply { isAccessible = true }
            .get(screens) as Runnable
        tick.run()
        assertTrue("a dark network wait reposted its clock tick", rig.posted.isEmpty())
    }
}
