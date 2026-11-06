package io.panelassistant.android

import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The on-panel sign-in's page is served by the panel's own HTTP server, which restarts briefly during a
 * reconfigure, so a failed load is retried quietly a few times before the panel says the sign-in page is
 * unavailable. That verdict replaces the sign-in, and whatever the person had typed with it, so it must
 * take failures in a row, not every hiccup since the sign-in first appeared.
 */
class PanelHaSignInClientTest {
    private val retries = mutableListOf<Long>()
    private var unreachable = 0
    private val client = PanelHaSignInClient(HA, 8889, object : PanelHaSignInClient.Host {
        override fun isCurrent(view: WebView) = true
        override fun retryLater(view: WebView, delayMs: Long) { retries += delayMs }
        override fun showUnreachable() { unreachable++ }
        override fun onSignedIn(view: WebView) = error("no sign-in completes here")
    })
    private val start = panelHaOAuthStartUrl(HA, 8889)
    private val page = RecordingWebView(start)
    private val mainFrame = Proxy.newProxyInstance(
        WebResourceRequest::class.java.classLoader,
        arrayOf(WebResourceRequest::class.java),
    ) { _, method, _ -> if (method.name == "isForMainFrame") true else null } as WebResourceRequest

    /** WebView reports the failed main frame, then finishes loading its own error page in its place. */
    private fun failedLoad() {
        client.onReceivedError(page, mainFrame, null)
        client.onPageFinished(page, start)
    }

    private fun cleanLoad() = client.onPageFinished(page, start)

    @Test fun onlyTheCurrentPortCallbackCanInspectSignInCompletion() {
        for (port in listOf(":8888", ":8890", "")) {
            client.onPageFinished(page, "http://127.0.0.1$port/api/v1/ha/oauth/callback?state=s&code=c")
        }
        assertEquals("other local servers cannot complete this sign-in", emptyList<String>(), page.calls)

        client.onPageFinished(page, "http://127.0.0.1:8889/api/v1/ha/oauth/callback?state=s&code=c")
        assertEquals(listOf("evaluateJavascript"), page.calls)
    }

    @Test fun hiccupsWithAWorkingPageBetweenThemDoNotAddUp() {
        repeat(4) { failedLoad() }
        cleanLoad()
        repeat(4) { failedLoad() }

        assertEquals("every failure retried quietly", 8, retries.size)
        assertEquals("the sign-in stayed", 0, unreachable)
    }

    @Test fun fiveFailuresInARowGiveTheVerdict() {
        repeat(5) { failedLoad() }

        assertEquals(List(4) { PanelHaSignInClient.LOAD_RETRY_MS }, retries)
        assertEquals("sign-in page unavailable, once", 1, unreachable)
    }
}
