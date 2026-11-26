package io.panelassistant.android

import android.net.Uri
import android.webkit.DashboardRefusedWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardRendererRefusedLoadTest {
    @Test fun refusedMainFrameRetriesWithAFreshDashboardLoadWithoutAWake() = Scenario().use { panel ->
        panel.finishHealthyLoad()
        panel.refuseMainFrame()
        assertEquals(listOf("loadData"), panel.navigations)

        panel.watchdog.advanceBy(4_999)
        assertEquals(listOf("loadData"), panel.navigations)
        panel.watchdog.advanceBy(1)
        assertEquals(
            "the watchdog should load Home Assistant instead of reloading the local reconnecting document",
            listOf("loadData", RETRY_NAVIGATION),
            panel.navigations,
        )
    }

    @Test fun refusalDoesNotMoveThePendingLoadDeadline() = Scenario().use { panel ->
        panel.rig.pacer.loadStarted()
        panel.web.calls.clear()
        panel.watchdog.advanceBy(10_000)
        panel.refuseMainFrame()
        panel.watchdog.advanceBy(14_999)
        assertEquals(listOf("loadData"), panel.navigations)

        panel.watchdog.advanceBy(1)
        assertEquals(listOf("loadData", RETRY_NAVIGATION), panel.navigations)
    }

    @Test fun refusalWhileDarkWaitsForWake() = Scenario().use { panel ->
        panel.finishHealthyLoad()
        panel.rig.pacer.onScreenChanged(false)
        panel.web.calls.clear()
        panel.refuseMainFrame()

        panel.watchdog.advanceBy(60_000)
        assertEquals(listOf("loadData"), panel.navigations)
        panel.rig.pacer.onScreenChanged(true)
        panel.watchdog.advanceBy(25_000)

        assertEquals(listOf("loadData", RETRY_NAVIGATION), panel.navigations)
    }

    @Test fun aRefusedSubresourceLeavesTheHealthyDashboardAlone() = Scenario().use { panel ->
        panel.finishHealthyLoad()
        panel.client.onReceivedError(panel.web, Request(mainFrame = false), DashboardRefusedWebResourceError())

        panel.watchdog.advanceBy(60_000)
        assertEquals(emptyList<String>(), panel.navigations)
    }

    private class Scenario : AutoCloseable {
        val rig = DashboardRendererRig()
        val web = RecordingWebView("$HA/lovelace")
        val navigations: List<String>
            get() = web.calls.filter { it == "loadData" || it == "reload" || it.startsWith("loadUrl ") }
        val watchdog = WatchdogClock()
        private val generation = rig.get("rendererGeneration") as Long
        val client: WebViewClient

        init {
            rig.phase = RendererPhase.NONE
            rig.set("reconnectSurfaceDark", { true })
            rig.show(web)
            rig.admitted()
            rig.setPaced("postWatchdog", watchdog.post)
            rig.setPaced("cancelWatchdog", watchdog.cancel)
            val clientClass = (1..9).mapNotNull {
                runCatching { Class.forName("io.panelassistant.android.DashboardActivity\$createWebView\$$it\$1") }.getOrNull()
            }.single { WebViewClient::class.java.isAssignableFrom(it) }
            val constructor = clientClass.declaredConstructors.single().apply { isAccessible = true }
            val arguments = constructor.parameterTypes.map { type ->
                when (type) {
                    DashboardActivity::class.java -> rig.activity
                    java.lang.Long.TYPE -> generation
                    Config::class.java -> rig.config
                    else -> error("Unexpected client constructor parameter $type")
                }
            }
            client = constructor.newInstance(*arguments.toTypedArray()) as WebViewClient
        }

        fun finishHealthyLoad() {
            rig.pacer.loadStarted()
            val session = rig.bridge.beginDocument(web, rig.config, generation)
            rig.call("onConnectionStatus", ExternalBusProtocol.ConnectionEvent.CONNECTED, generation, session)
            web.calls.clear()
        }

        fun refuseMainFrame() {
            client.onReceivedError(web, Request(mainFrame = true), DashboardRefusedWebResourceError())
            // WebView starts the replacement local document after the error callback.
            client.onPageStarted(web, "data:text/html,reconnecting", null)
            client.onPageCommitVisible(web, "data:text/html,reconnecting")
        }

        override fun close() = rig.close()
    }

    private class WatchdogClock {
        private var now = 0L
        private val tasks = mutableListOf<Pair<Runnable, Long>>()
        val post: (Runnable, Long) -> Boolean = { task, delay -> tasks.add(task to now + delay) }
        val cancel: (Runnable) -> Unit = { task -> tasks.removeAll { it.first === task } }

        fun advanceBy(elapsed: Long) {
            val end = now + elapsed
            while (true) {
                val next = tasks.minByOrNull { it.second }?.takeIf { it.second <= end } ?: break
                tasks.remove(next)
                now = next.second
                next.first.run()
            }
            now = end
        }
    }

    private companion object {
        const val RETRY_NAVIGATION = "loadUrl $HA/lovelace?external_auth=1"
    }

    private class Request(private val mainFrame: Boolean) : WebResourceRequest {
        override fun isForMainFrame() = mainFrame
        override fun getUrl(): Uri = error("The callback does not read the URL")
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

}
