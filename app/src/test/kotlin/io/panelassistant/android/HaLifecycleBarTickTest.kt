package io.panelassistant.android

import android.widget.LinearLayout
import android.widget.TextView
import io.panelassistant.android.DashboardRendererRig.Companion.allocate
import io.panelassistant.android.control.BuiltinDashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The restart card re-reads its state once a second, because nothing pushes when a grace runs out. A
 * card belongs to one activity: while a superseded activity's card is still attached, its tick must read
 * that activity's renderer state, as the activity's own redraw does, never its replacement's.
 */
class HaLifecycleBarTickTest {
    private val old = BuiltinDashboard.acquireActivityOwner()
    private val replacement = BuiltinDashboard.acquireActivityOwner()

    @After fun release() {
        RendererAdmissionRuntime.reset()
        BuiltinDashboard.releaseActivityOwner(replacement)
    }

    @Test fun aSupersededCardsTickDoesNotShowItsReplacementsState() {
        // The replacement admitted on a cached version and has not connected: its own card says it cannot
        // reach Home Assistant. That is no state of the old activity's.
        RendererAdmissionRuntime.record(replacement, RendererAdmissionState.ADMITTED, 1L, admittedOnCachedVersion = true)
        val shown = mutableListOf<Boolean>()
        val bar = card(ownedBy = old, onVisibilityChanged = { shown += it })
        HaLifecycleBar::class.java.getDeclaredField("visible").apply { isAccessible = true }.set(bar, true)
        val tick = HaLifecycleBar::class.java.getDeclaredField("hide").apply { isAccessible = true }.get(bar) as Runnable

        // Drawing a card needs a real view; a tick that hides it does not.
        tick.run()

        assertEquals("the old card hid instead of showing the replacement's state", listOf(false), shown)
    }

    /** The card as [HaLifecycleBar.attach] builds it, minus the views the JVM cannot inflate. */
    private fun card(ownedBy: Long, onVisibilityChanged: (Boolean) -> Unit): HaLifecycleBar {
        val notice = allocate(Notice::class.java)
        Notice::class.java.getDeclaredField("view").apply { isAccessible = true }.set(notice, allocate(LinearLayout::class.java))
        val ownState: () -> RendererAdmissionRuntime.Live? = { RendererAdmissionRuntime.current()?.takeIf { it.owner == ownedBy } }
        val ctor = HaLifecycleBar::class.java.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        val args = ctor.parameterTypes.map { type ->
            when (type) {
                Notice::class.java -> notice
                TextView::class.java -> allocate(TextView::class.java)
                NoticeTrack::class.java -> allocate(NoticeTrack::class.java)
                Function1::class.java -> onVisibilityChanged
                Function0::class.java -> ownState
                else -> error("unexpected card part $type")
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as HaLifecycleBar
    }
}
