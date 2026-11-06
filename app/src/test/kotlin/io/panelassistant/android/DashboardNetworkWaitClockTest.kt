package io.panelassistant.android

import android.os.Handler
import android.widget.FrameLayout
import android.widget.TextView
import io.panelassistant.android.DashboardRendererRig.Companion.allocate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the native waiting screen's actual tick through the activity's real screen-state fan-out. */
class DashboardNetworkWaitClockTest {
    private var nowMs = 100_000L
    private val due = mutableListOf<Runnable>()
    private val painted = mutableListOf<String>()
    private var networkReads = 0

    private fun waiting(rig: DashboardRendererRig): DashboardStatusScreens {
        val screens = DashboardStatusScreens(
            rig.activity, allocate(Handler::class.java), CoroutineScope(Job()), AdmissionRetryPolicy(),
            object : DashboardStatusScreens.Host {
                override val activityOwner get() = rig.owner
                override val destroyed = false
                override val hasWeb = false
                override var root: FrameLayout? = null
                override fun teardownWeb() = error("unexpected teardown")
                override fun detachLifecycleBar() = Unit
                override fun activeNetworkPresent(): Boolean { networkReads++; return false }
                override fun retryAdmission(resetBackoff: Boolean) = error("unexpected retry")
                override fun onAdmissionRetryDue() = error("unexpected retry")
                override fun redrawCoverPage() = Unit
            },
            screenAwake = { rig.pacer.screenAwake }, now = { nowMs },
            postWaiting = { tick, delay -> assertEquals(1_000L, delay); due += tick },
            cancelWaiting = { tick -> due.removeAll { it === tick } },
            paintWaitingHero = { _, text -> painted += text },
        )
        // The mockable Android jar cannot inflate a Notice. Supply only its two clock views; all
        // scheduling, ownership, network reads and elapsed-time formatting remain production calls.
        fun put(name: String, value: Any) = DashboardStatusScreens::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(screens, value)
        put("waitingStatus", allocate(TextView::class.java))
        put("waitingHero", allocate(TextView::class.java))
        put("waitingStartedAt", nowMs)
        rig.set("statusScreens", screens)
        return screens
    }

    private fun seconds(n: Int) = repeat(n) {
        nowMs += 1_000L
        val ticks = due.toList()
        due.clear()
        ticks.forEach(Runnable::run)
    }

    @Test fun activityPausesInDarkAndWakeCatchesUpWithoutDuplicatingTheClock() {
        DashboardRendererRig().use { rig ->
            rig.setPaced("screenAwake", true)
            waiting(rig).screenChanged(true)
            seconds(2)
            assertEquals(listOf("0:00", "0:01", "0:02"), painted)
            rig.pace("onScreenChanged", false)
            val readsBeforeDark = networkReads
            seconds(90)
            assertEquals(0, due.size)
            assertEquals(readsBeforeDark, networkReads)
            assertEquals(3, painted.size)
            rig.pace("onScreenChanged", true)
            assertEquals("1:32", painted.last())
            rig.pace("onScreenChanged", true)
            seconds(1)
            assertEquals("1:33", painted.last())
            assertEquals(1, due.size)
        }
    }

    @Test fun startingDarkAndAnAlreadyDequeuedTickDoNoInvisibleWork() {
        DashboardRendererRig().use { rig ->
            rig.setPaced("screenAwake", false)
            val screens = waiting(rig)
            screens.screenChanged(true) // caller cannot override the actual screen state
            seconds(30)
            assertEquals(emptyList<String>(), painted)
            assertEquals(0, networkReads)
            assertEquals(0, due.size)
            rig.pace("onScreenChanged", true)
            val dequeued = due.single()
            rig.pace("onScreenChanged", false)
            dequeued.run()
            assertEquals(listOf("0:30"), painted)
            assertEquals(1, networkReads)
            assertEquals(0, due.size)
        }
    }

    @Test fun recoveryCancelsTheClockAndWakeDoesNotRestartIt() {
        DashboardRendererRig().use { rig ->
            rig.setPaced("screenAwake", true)
            val screens = waiting(rig)
            screens.screenChanged(true)
            assertEquals(1, due.size)
            rig.pace("onScreenChanged", false)
            seconds(30)
            // No learned sample is written for an unstarted wait; use the actual recovery cleanup.
            DashboardStatusScreens::class.java.getDeclaredField("waitingStartedAt")
                .apply { isAccessible = true }.setLong(screens, 0L)
            screens.endNetworkWait()
            val paintsBeforeWake = painted.toList()
            rig.pace("onScreenChanged", true)
            seconds(5)
            assertEquals(paintsBeforeWake, painted)
            assertEquals(0, due.size)
        }
    }
}
