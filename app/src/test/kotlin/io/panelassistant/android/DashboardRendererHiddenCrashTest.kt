package io.panelassistant.android

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import io.panelassistant.android.control.BuiltinDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A renderer that dies while the dashboard is stopped (another app in front, or the screen off) is rebuilt
 * when the activity next starts, not at once: a hidden rebuild blocked the bench X2i's main thread for about
 * 8 s and raised an ANR dialog over the app in front (2026-10-03). The activity's own lifecycle registry
 * carries it from stopped to started, so the rebuild is reached exactly as Android's ON_START reaches it.
 * A rebuild is observed as the involuntary reload it records and the crash budget it spends.
 */
class DashboardRendererHiddenCrashTest {
    private class Detail : RenderProcessGoneDetail() {
        override fun didCrash() = false
        override fun rendererPriorityAtExit() = 0
    }

    private fun DashboardRendererRig.lifecycle(state: Lifecycle.State): LifecycleRegistry =
        lifecycle.also { it.currentState = state }

    private fun DashboardRendererRig.crash() {
        val generation = (get("rendererGate") as RendererGenerationGate).open().also { set("rendererGeneration", it) }
        val dead = DashboardRendererRig.allocate(WebView::class.java)
        set("web", dead)
        phase = RendererPhase.CONNECTED
        val clientClass = (1..9).mapNotNull {
            runCatching { Class.forName("io.panelassistant.android.DashboardActivity\$createWebView\$$it\$1") }.getOrNull()
        }.single { WebViewClient::class.java.isAssignableFrom(it) }
        val ctor = clientClass.declaredConstructors.single().apply { isAccessible = true }
        val client = ctor.newInstance(*ctor.parameterTypes.map { type ->
            when (type) {
                DashboardActivity::class.java -> activity
                java.lang.Long.TYPE -> generation
                else -> null
            }
        }.toTypedArray()) as WebViewClient
        client.onRenderProcessGone(dead, Detail())
    }

    private fun reloads(): Long = BuiltinDashboard::class.java.getDeclaredField("reloadsTotal")
        .apply { isAccessible = true }.get(BuiltinDashboard) as Long

    @Test fun aRendererThatDiesWhileTheDashboardIsStoppedIsRebuiltWhenItNextStarts() = DashboardRendererRig(setupQuestion = true).use { rig -> // the rebuild reaches the entity hold, which this rig paints
        BuiltinDashboard.clearRendererLatch()
        val registry = rig.lifecycle(Lifecycle.State.CREATED) // onStop has run: another app is in front
        val before = reloads()

        rig.crash()

        assertNull("the dead WebView was not released", rig.get("web"))
        assertEquals("a stopped dashboard rebuilt its renderer at once", before, reloads())

        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("the next start did not rebuild the renderer", before + 1, reloads())

        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("a later start rebuilt the renderer again", before + 1, reloads())
    }

    @Test fun aRendererThatDiesWhileTheDashboardIsVisibleIsRebuiltAtOnceWithinTheBudget() = DashboardRendererRig(setupQuestion = true).use { rig -> // the rebuild reaches the entity hold, which this rig paints
        BuiltinDashboard.clearRendererLatch()
        rig.lifecycle(Lifecycle.State.STARTED) // paused behind a dialog is still visible
        val before = reloads()
        repeat(BuiltinDashboard.MAX_REBUILDS) { rig.crash() }
        assertEquals("a visible dashboard did not rebuild within the budget", before + BuiltinDashboard.MAX_REBUILDS, reloads())
        rig.crash()
        assertEquals("a crash past the budget rebuilt anyway", before + BuiltinDashboard.MAX_REBUILDS, reloads())
        BuiltinDashboard.clearRendererLatch()
    }
}
