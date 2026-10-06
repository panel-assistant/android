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

    private fun cardAt(tracker: HaLifecycle, nowMs: Long, stepFrom: Int? = null, dashboardConnected: Boolean = false): HaLifecycleCard? {
        val snap = tracker.snapshot(nowMs)
        val state = haLifecycleNoticeState(snap, live(dashboardConnected)) ?: return null
        return haLifecycleCard(state, snap, dashboardConnected, stepFrom)
    }

    @Test fun aMeasuredRestartCountsDownWhileStoppingFillsTheFirstStep() {
        val tracker = HaLifecycle().apply { notice(HaLifecyclePhase.SHUTTING_DOWN, 0L) }
        val card = cardAt(tracker, 25_000L)!!
        assertEquals(HaRestartStep.STOPPING, card.step)
        assertEquals(75_000L, card.remainingMs)
        assertEquals(100_000L, card.usualMs)
        // Stopping fills over the first half of the usual time; nothing later has started.
        assertEquals(listOf(max / 2, 0, 0), card.fills)
    }

    @Test fun startingFillsFromWhereTheRestartWasWhenItStartedToTheUsualTime() {
        val tracker = HaLifecycle().apply {
            notice(HaLifecyclePhase.SHUTTING_DOWN, 0L)
            notice(HaLifecyclePhase.STARTING, 40_000L, elapsedMs = 40_000L)
        }
        val from = (40_000L * max / 100_000L).toInt()
        val atStart = cardAt(tracker, 40_000L, from)!!
        assertEquals(HaRestartStep.STARTING, atStart.step)
        assertEquals("a finished step is full and the new one starts empty", listOf(max, 0, 0), atStart.fills)
        val halfway = cardAt(tracker, 70_000L, from)!!
        assertEquals(listOf(max, max / 2, 0), halfway.fills)
        assertEquals(30_000L, halfway.remainingMs)
    }

    @Test fun anOverdueRestartSitsFullAndCountsUpWithoutRunningBackwards() {
        val tracker = HaLifecycle().apply { notice(HaLifecyclePhase.STARTING, 0L, elapsedMs = 0L, expectedMs = 30_000L) }
        val card = cardAt(tracker, 54_000L, stepFrom = 0)!!
        assertEquals(listOf(max, max, 0), card.fills)
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
        val from = (60_000L * max / 100_000L).toInt()
        val card = cardAt(tracker, 80_000L, stepFrom = from, dashboardConnected = true)!!
        assertEquals(HaRestartStep.RELOADING, card.step)
        assertEquals("Stopping and Starting are done; Reloading fills toward the usual time",
            listOf(max, max, max / 2), card.fills)
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
        assertEquals(listOf(max, max, max / 2), card.fills)
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
