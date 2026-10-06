package io.panelassistant.android

import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleNotice
import io.panelassistant.android.sensors.HaLifecyclePhase
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart card, driven through the lifecycle tracker it reads: Panel Assistant notices arrive, the
 * tracker's own clock advances, and the card's step, track and times come from its snapshot.
 */
class HaLifecycleCardTest {
    private val max = HA_LIFECYCLE_PROGRESS_MAX

    private fun HaLifecycle.notice(phase: HaLifecyclePhase, nowMs: Long, elapsedMs: Long? = 0L, expectedMs: Long? = 100_000L) =
        onNativeNotice(HaLifecycleNotice(phase, HaLifecycleReason.RESTART, elapsedMs, expectedMs), nowMs)

    private fun cardAt(tracker: HaLifecycle, nowMs: Long, dashboardConnected: Boolean = false): HaLifecycleCard? {
        val snap = tracker.snapshot(nowMs)
        val state = haLifecycleNoticeState(snap, live(dashboardConnected)) ?: return null
        return haLifecycleCard(state, snap, dashboardConnected)
    }

    @Test fun aMeasuredRestartCountsDownWhileStoppingFillsTheFirstStep() {
        val tracker = HaLifecycle().apply { notice(HaLifecyclePhase.SHUTTING_DOWN, 0L) }
        val card = cardAt(tracker, 25_000L)!!
        assertEquals(HaRestartStep.STOPPING, card.step)
        assertEquals(75_000L, card.remainingMs)
        assertEquals(100_000L, card.usualMs)
        // One timeline over three parts: a quarter of the usual time is three quarters of the first part.
        assertEquals(listOf(max * 3 / 4, 0, 0), card.fills)
    }

    @Test fun panelsAtTheSameMomentShowTheSameTrackWhateverStepEachIsIn() {
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
            notice(HaLifecyclePhase.STARTING, 40_000L, elapsedMs = 40_000L)
        }
        // At 80% of the usual time one panel's dashboard has reconnected and another's has not.
        val reloading = cardAt(tracker, 80_000L, dashboardConnected = true)!!
        val starting = cardAt(tracker, 80_000L, dashboardConnected = false)!!
        assertEquals(HaRestartStep.RELOADING, reloading.step)
        assertEquals(HaRestartStep.STARTING, starting.step)
        assertEquals(listOf(max, max, max * 2 / 5), reloading.fills)
        assertEquals("the track is the clock, not this panel's step", reloading.fills, starting.fills)
        assertEquals(20_000L, starting.remainingMs)
    }

    @Test fun aFinishedStepIsFullEvenWhenTheClockIsBehindIt() {
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
            notice(HaLifecyclePhase.STARTING, 10_000L, elapsedMs = 10_000L)
        }
        // A fifth of the usual time would fill only 60% of Stopping, but Stopping has finished.
        assertEquals(listOf(max, 0, 0), cardAt(tracker, 20_000L)!!.fills)
    }

    @Test fun anOverdueRestartSitsFullAndCountsUpWithoutRunningBackwards() {
        val tracker = HaLifecycle().apply { notice(HaLifecyclePhase.STARTING, 0L, elapsedMs = 0L, expectedMs = 30_000L) }
        val card = cardAt(tracker, 54_000L)!!
        assertEquals("the usual time is used up; the step name and pill say where it is", listOf(max, max, max), card.fills)
        assertEquals(HaRestartStep.STARTING, card.step)
        assertNull(card.remainingMs)
        assertEquals(24_000L, card.overdueMs)
        assertEquals("+0:24", "+" + haLifecycleClock(card.overdueMs!!))
    }

    @Test fun anUnmeasuredRestartShowsItsStepWithNoCountdownOrPartialFill() {
        val tracker = HaLifecycle().apply { notice(HaLifecyclePhase.STARTING, 0L, elapsedMs = null, expectedMs = null) }
        val card = cardAt(tracker, 20_000L)!!
        assertEquals(HaRestartStep.STARTING, card.step)
        assertEquals(listOf(max, 0, 0), card.fills)
        assertNull(card.remainingMs)
        assertNull(card.overdueMs)
        assertNull(card.usualMs)
    }

    @Test fun aDashboardReconnectingWhileHomeAssistantStartsIsTheReloadingStepAndKeepsCounting() {
        // Seen on hardware: the dashboard reconnected a minute before Home Assistant reported ready.
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
            notice(HaLifecyclePhase.STARTING, 40_000L, elapsedMs = 40_000L)
        }
        val card = cardAt(tracker, 80_000L, dashboardConnected = true)!!
        assertEquals(HaRestartStep.RELOADING, card.step)
        assertEquals(listOf(max, max, max * 2 / 5), card.fills)
        assertEquals("Home Assistant is not ready yet, so the countdown goes on", 20_000L, card.remainingMs)
        assertNull(card.restartedInMs)
        assertNull("the card closes once Home Assistant is ready with the dashboard connected",
            cardAt(tracker.apply { notice(HaLifecyclePhase.READY, 90_000L) }, 91_000L, dashboardConnected = true))
    }

    @Test fun backOnlineIsTheReloadingStepAndSaysHowLongTheRestartTook() {
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.STARTING, 0L, elapsedMs = 50_000L)
            notice(HaLifecyclePhase.READY, 63_000L)
        }
        val card = cardAt(tracker, 67_000L)!!
        assertEquals(HaRestartStep.RELOADING, card.step)
        assertNull("no countdown once Home Assistant is up", card.remainingMs)
        assertEquals("past the usual time the track is full", listOf(max, max, max), card.fills)
        assertEquals(113_000L, card.restartedInMs)
    }

    @Test fun theReloadingStepEndsWhenTheDashboardReconnects() {
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.STARTING, 0L)
            notice(HaLifecyclePhase.READY, 10_000L)
        }
        val snap = tracker.snapshot(11_000L)
        assertEquals(HaLifecycleState.BACK_ONLINE, haLifecycleNoticeState(snap, null))
        assertEquals(HaLifecycleState.BACK_ONLINE, haLifecycleNoticeState(snap, live(frontendConnected = false)))
        assertNull(haLifecycleNoticeState(snap, live(frontendConnected = true)))
    }

    @Test fun anUnexplainedOutageHasNoTrack() {
        val tracker = HaLifecycle().apply { onDisconnected(0L) }
        val card = cardAt(tracker, 10_000L)!!
        assertNull(card.step)
        assertEquals(listOf(0, 0, 0), card.fills)
    }

    @Test fun timesAreExactNeverRoundedToAVaguerUnit() {
        assertEquals(HaExactDuration(null, 113L), haExactDuration(113_308L))
        assertEquals(HaExactDuration(null, 180L), haExactDuration(180_000L))
        assertEquals(HaExactDuration(6L, 20L), haExactDuration(380_000L))
        assertEquals("1:13", haLifecycleClock(72_100L))
        assertEquals("0:01", haLifecycleClock(1L))
    }

    @Test fun theCardScalesWithASmallPanelButIsCappedAtTheLogicalDensity() {
        assertEquals(1f, haLifecycleScale(480f, 1f), 0.001f)
        assertEquals(1.4125f, haLifecycleScale(1200f, 1.4125f), 0.001f)
        assertEquals("a bigger screen alone does not grow the card", 1f, haLifecycleScale(4000f, 1f), 0.001f)
        assertEquals(2f / 3f, haLifecycleScale(320f, 1f), 0.001f)
        assertTrue("a broken density never collapses the card", haLifecycleScale(1200f, 0f) > 0f)
    }

    private fun live(frontendConnected: Boolean) = RendererAdmissionRuntime.Live(1L, null, frontendConnected, null)
}
