package io.panelassistant.android

import android.app.Activity
import android.os.Handler
import android.widget.TextView
import io.panelassistant.android.DashboardRendererRig.Companion.allocate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reconnecting cover's "unreachable for" clock: it counts once a second while the screen is lit, stops
 * in the dark (including a cover first put up there), and catches up from the same origin on wake.
 */
class DashboardWebCoverTest {
    private var awake = true
    private var attached = true
    private var nowMs = 100_000L
    private val due = mutableListOf<Runnable>()
    private val painted = mutableListOf<String>()
    private val hero = allocate(TextView::class.java)

    private fun cover(screenAwake: () -> Boolean = { awake }) = DashboardWebCover(
        allocate(Activity::class.java),
        allocate(Handler::class.java),
        screenAwake = screenAwake,
        now = { nowMs },
        post = { tick, delayMs -> assertEquals(1_000L, delayMs); due += tick },
        cancel = { tick -> due.removeAll { it === tick } },
        paint = { _, text -> if (attached) painted += text; attached },
    )

    /** A second passes and whatever was posted for it runs, as the activity's Handler would. */
    private fun seconds(n: Int) = repeat(n) {
        nowMs += 1_000L
        val now = due.toList()
        due.clear()
        now.forEach(Runnable::run)
    }

    @Test fun whileTheScreenIsLitTheClockCountsUpEverySecond() {
        val cover = cover()
        cover.countUp(hero, nowMs - 5_000L)

        seconds(3)

        assertEquals(listOf("0:06", "0:07", "0:08"), painted)
        assertEquals("one tick waits for the next second", 1, due.size)
    }

    @Test fun goingDarkStopsTheClockUntilTheScreenWakes() {
        val cover = cover()
        cover.countUp(hero, nowMs)
        seconds(1)

        awake = false
        cover.screenChanged(false)
        seconds(120)

        assertEquals("nothing paints in the dark", listOf("0:01"), painted)
        assertEquals("no tick waits in the dark", 0, due.size)
    }

    @Test fun aCoverFirstPutUpInTheDarkNeverTicksThere() {
        awake = false
        val cover = cover()
        cover.countUp(hero, nowMs)

        seconds(120)

        assertEquals(emptyList<String>(), painted)
        assertEquals(0, due.size)
    }

    @Test fun onWakeTheClockRepaintsAtOnceFromTheSameOriginAndCarriesOn() {
        awake = false
        val cover = cover()
        cover.countUp(hero, nowMs)
        seconds(90)

        awake = true
        cover.screenChanged(true)
        assertEquals("the wake repaints the whole outage, not a restarted count", listOf("1:30"), painted)

        cover.screenChanged(true) // a repeated wake must not start a second clock
        seconds(2)

        assertEquals(listOf("1:30", "1:30", "1:31", "1:32"), painted)
        assertEquals(1, due.size)
    }

    @Test fun hidingTheCoverStopsTheClockAndALaterWakeDoesNotRestartIt() {
        val cover = cover()
        cover.countUp(hero, nowMs)

        cover.hide()
        assertEquals("hiding left a tick waiting", 0, due.size)
        cover.screenChanged(true)
        seconds(5)

        assertEquals(emptyList<String>(), painted)
        assertEquals(0, due.size)
    }

    @Test fun aHeroThatHasLeftItsWindowEndsTheClock() {
        val cover = cover()
        cover.countUp(hero, nowMs)

        attached = false
        seconds(5)

        assertEquals(emptyList<String>(), painted)
        assertEquals(0, due.size)
    }

    @Test fun theActivitysScreenFanOutPausesAndWakesTheCover() {
        DashboardRendererRig().use { rig ->
            val cover = cover(screenAwake = { rig.pacer.screenAwake })
            rig.set("webCover\$delegate", lazyOf(cover))
            rig.setPaced("screenAwake", true)
            cover.countUp(hero, nowMs)

            rig.pace("onScreenChanged", false)
            seconds(30)
            assertEquals("the screen-off fan-out did not stop the clock", 0, due.size)
            assertEquals(emptyList<String>(), painted)

            rig.pace("onScreenChanged", true)
            assertEquals("the screen-on fan-out did not repaint the clock", listOf("0:30"), painted)
            assertEquals(1, due.size)
        }
    }
}
