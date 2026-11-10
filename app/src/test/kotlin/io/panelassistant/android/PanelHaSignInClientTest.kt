package io.panelassistant.android

import android.content.ContextWrapper
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** A sign-in page whose callback reports [status] the way the panel's callback page marks it. */
    private class CallbackPage(private val status: String) : WebView(ContextWrapper(null)) {
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.onReceiveValue("\"$status\"")
        }
    }

    private val callback = "http://127.0.0.1:8889/api/v1/ha/oauth/callback?state=s&code=c"

    @Test fun aFailedCallbackStartsAFreshSignInInsteadOfStrandingThePanel() {
        // An install restarted the panel's server mid sign-in, so the callback no longer knows the link.
        client.onPageFinished(CallbackPage("failure"), callback)

        assertEquals("a fresh sign-in, after a moment to read why", listOf(PanelHaSignInClient.CALLBACK_RESTART_MS), retries)
        assertEquals(0, unreachable)
    }

    @Test fun aCallbackThatKeepsFailingStopsRestarting() {
        repeat(PanelHaSignInClient.CALLBACK_RESTARTS_MAX + 1) { client.onPageFinished(CallbackPage("failure"), callback) }

        assertEquals(List(PanelHaSignInClient.CALLBACK_RESTARTS_MAX) { PanelHaSignInClient.CALLBACK_RESTART_MS }, retries)
        assertEquals("then the panel's verdict, once", 1, unreachable)
    }

    /**
     * Home Assistant's sign-in page names the address it hands the credential to. Opened on 127.0.0.1 it told
     * the owner "You're about to give http://127.0.0.1:8888 access" (ha-integration #31), so the sign-in opens on
     * the panel's LAN address, and the callback that comes back there must complete it.
     */
    @Test fun aSignInOpenedOnThePanelsLanAddressCompletesThere() {
        var signedIn = 0
        val restarts = mutableListOf<Long>()
        val lan = PanelHaSignInClient(HA, 8889, object : PanelHaSignInClient.Host {
            override fun isCurrent(view: WebView) = true
            override fun retryLater(view: WebView, delayMs: Long) { restarts += delayMs }
            override fun showUnreachable() { unreachable++ }
            override fun onSignedIn(view: WebView) { signedIn++ }
        }, panelHost = panelSignInHost(listenerOffLan = false, lanIpv4 = "192.168.1.20"))

        assertTrue(lan.startUrl, lan.startUrl.startsWith("http://192.168.1.20:8889/api/v1/ha/oauth/panel-start?"))

        val otherPanel = RecordingWebView(lan.startUrl)
        lan.onPageFinished(otherPanel, "http://192.168.1.21:8889/api/v1/ha/oauth/callback?state=s&code=c")
        assertEquals("another address's callback is not this sign-in's", emptyList<String>(), otherPanel.calls)

        lan.onPageFinished(CallbackPage("success"), "http://192.168.1.20:8889/api/v1/ha/oauth/callback?state=s&code=c")
        assertEquals("the callback on the panel's own address completes the sign-in", 1, signedIn)
        assertEquals(emptyList<Long>(), restarts)
    }

    @Test fun aSuccessfulCallbackHandsOverWithoutRestarting() {
        var signedIn = 0
        val signingIn = PanelHaSignInClient(HA, 8889, object : PanelHaSignInClient.Host {
            override fun isCurrent(view: WebView) = true
            override fun retryLater(view: WebView, delayMs: Long) { retries += delayMs }
            override fun showUnreachable() { unreachable++ }
            override fun onSignedIn(view: WebView) { signedIn++ }
        })
        signingIn.onPageFinished(CallbackPage("success"), callback)

        assertEquals(1, signedIn)
        assertEquals(emptyList<Long>(), retries)
    }
}
