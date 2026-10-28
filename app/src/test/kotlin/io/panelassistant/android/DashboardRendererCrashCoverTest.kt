package io.panelassistant.android

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A renderer crash while the Reconnecting cover is up. The crash is delivered to the dashboard
 * WebView's own [WebViewClient], built by [DashboardActivity.createWebView], on an activity whose state
 * is exactly what [DashboardActivity.showReconnecting] leaves behind: the reconnecting phase and the
 * cover standing over the current WebView's container.
 *
 * The rebuild that follows creates a new container with `setContentView`. If the crash leaves the phase
 * reconnecting, the new WebView's first network error reaches `showReconnecting`, which returns on that
 * stale phase, so nothing covers Chromium's own error page; and the old cover stays in the detached
 * container. A teardown ends the phase on every rebuild path; the crash path must as well.
 *
 * Runs on [DashboardRendererRig], which releases its activity owner when the test ends.
 */
class DashboardRendererCrashCoverTest {
    private class Detail : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = 0
    }

    @Test fun aRendererCrashUnderTheReconnectingCoverClearsItSoTheRebuiltWebViewCanShowItAgain() = DashboardRendererRig().use { rig ->
        val gate = rig.get("rendererGate") as RendererGenerationGate
        val generation = rig.get("rendererGeneration") as Long
        val oldWeb = DashboardRendererRig.allocate(WebView::class.java)
        rig.set("web", oldWeb)
        (rig.get("shownPage") as ShownPageTracker).onCommitVisible("data:text/html,reconnecting")
        rig.phase = RendererPhase.RECONNECTING // showReconnecting ran for the first network error
        rig.set("reconnectingSinceMs", 1L)
        rig.coverShown() // the cover showReconnecting put up over the crashed WebView's container

        // The client is an anonymous class whose compiler ordinal shifts as createWebView changes.
        val clientClass = (1..9).mapNotNull {
            runCatching { Class.forName("io.panelassistant.android.DashboardActivity\$createWebView\$$it\$1") }.getOrNull()
        }.single { WebViewClient::class.java.isAssignableFrom(it) }
        val ctor = clientClass.declaredConstructors.single().apply { isAccessible = true }
        val args = ctor.parameterTypes.map { type ->
            when (type) {
                DashboardActivity::class.java -> rig.activity
                java.lang.Long.TYPE -> generation
                else -> null
            }
        }
        val client = ctor.newInstance(*args.toTypedArray()) as WebViewClient

        assertTrue(client.onRenderProcessGone(oldWeb, Detail()))

        // The owned-crash branch ran: the generation is retired and the dead WebView released.
        assertFalse("crash did not retire the renderer generation", gate.owns(generation))
        assertNull("crash did not release the dead WebView", rig.get("web"))

        // What the rebuilt WebView's first main-frame network error needs: showReconnecting returns
        // early while the phase is still reconnecting, and the old cover must not stay behind in the
        // detached container.
        assertEquals(
            "after the renderer-crash rebuild: [still reconnecting (showReconnecting returns early), " +
                "old cover still held in the crashed WebView's container]",
            listOf(false, false),
            listOf(rig.phase == RendererPhase.RECONNECTING, rig.coverUp),
        )
    }
}
