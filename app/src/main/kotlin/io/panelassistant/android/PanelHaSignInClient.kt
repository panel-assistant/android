package io.panelassistant.android

import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.panelassistant.android.http.HA_OAUTH_CALLBACK_PATH
import java.net.URI
import java.net.URLEncoder

/**
 * The client of the bare on-panel Home Assistant sign-in WebView: it keeps the page to the OAuth flow for
 * [haUrl], hands over once the callback reports success, and retries a failed page load quietly before
 * giving the panel's verdict after [LOAD_RETRIES_MAX] failures in a row. A callback that reports anything
 * but success (an expired or superseded link, or one the panel's server no longer knows after a restart)
 * starts the sign-in again from a fresh start page: its "back" link points at a page this WebView may not
 * open, so without the restart the panel would stay on that message for good.
 */
internal class PanelHaSignInClient(
    private val haUrl: String,
    private val httpPort: Int,
    private val host: Host,
    /** The panel host this sign-in was opened on ([panelSignInHost]); its callback comes back to it. */
    private val panelHost: String = PANEL_SIGN_IN_LOOPBACK_HOST,
) : WebViewClient() {
    interface Host {
        /** Whether [view] is still the activity's current sign-in WebView. */
        fun isCurrent(view: WebView): Boolean

        /** Load [startUrl] into [view] again after [delayMs], if it is still current then. */
        fun retryLater(view: WebView, delayMs: Long)

        /** The sign-in page keeps failing to load: the panel's "sign-in page unavailable" screen. */
        fun showUnreachable()

        /** Home Assistant's callback reported the sign-in complete. */
        fun onSignedIn(view: WebView)
    }

    /** The sign-in start page: the first load and every restart, on the one host the callback is checked against. */
    val startUrl: String = panelHaOAuthStartUrl(haUrl, httpPort, panelHost)

    private var loadRetries = 0

    /** Callbacks that did not report success. Each restart needs the person to sign in again, so this
     *  bound only stops a callback that keeps failing on its own from restarting the sign-in for ever. */
    private var callbackRestarts = 0

    /** The current main frame failed. WebView then finishes loading its own error page in its place, so a
     *  finished page counts as working only when no error came first. */
    private var loadFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!host.isCurrent(view)) return true
        return !panelHaOAuthNavigationAllowed(haUrl, request.url.toString(), httpPort, panelHost)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!host.isCurrent(view)) return
        // A page that loaded restores the budget: it counts failures in a row, not every hiccup of a long
        // sign-in, whose verdict would throw away what the person had typed.
        if (!loadFailed) loadRetries = 0
        loadFailed = false
        if (!isPanelHaOAuthCallback(url, httpPort, panelHost)) return
        view.evaluateJavascript("document.body&&document.body.dataset?document.body.dataset.haOauthStatus:\"\"") { result ->
            if (!host.isCurrent(view)) return@evaluateJavascript
            when (result?.trim('"')) {
                "success" -> host.onSignedIn(view)
                "failure" -> restartAfterFailedCallback(view)
            }
        }
    }

    private fun restartAfterFailedCallback(view: WebView) {
        if (callbackRestarts >= CALLBACK_RESTARTS_MAX) {
            host.showUnreachable()
            return
        }
        callbackRestarts++
        Log.i(DashboardActivity.TAG, "panel sign-in callback failed — fresh sign-in $callbackRestarts/$CALLBACK_RESTARTS_MAX")
        // Long enough to read why, short enough that nobody is left waiting on a dead end.
        host.retryLater(view, CALLBACK_RESTART_MS)
    }

    // The error is never read: which failure it was does not change the retry.
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
        if (!host.isCurrent(view) || !request.isForMainFrame) return
        loadFailed = true
        // A main-frame error here is usually TRANSIENT: the sign-in start page is served by this panel's own
        // local HTTP server, which restarts briefly during a config reconfigure — exactly when a first-run
        // user is mid-setup. On hardware review that produced a terminal "sign-in unavailable" over a sign-in
        // the user may have been in the middle of. Retry quietly first; only a persistent failure earns the
        // verdict.
        if (loadRetries < LOAD_RETRIES_MAX) {
            loadRetries++
            Log.i(DashboardActivity.TAG, "panel sign-in page load failed — retry $loadRetries/$LOAD_RETRIES_MAX")
            host.retryLater(view, LOAD_RETRY_MS)
            return
        }
        host.showUnreachable()
    }

    companion object {
        const val LOAD_RETRIES_MAX = 4
        const val LOAD_RETRY_MS = 4_000L
        const val CALLBACK_RESTARTS_MAX = 5
        const val CALLBACK_RESTART_MS = 5_000L
    }
}

/** The loopback host the on-panel sign-in falls back to when the panel has no LAN IPv4 address to name. */
internal const val PANEL_SIGN_IN_LOOPBACK_HOST = "127.0.0.1"

/**
 * The host the on-panel sign-in is opened on. Home Assistant's sign-in page names the address it hands the
 * credential to, so on 127.0.0.1 the owner read "You're about to give http://127.0.0.1:8888 access", which says
 * nothing about which device is asking. The panel's LAN IPv4 address names the panel. Only while the `:8888`
 * listener answers the LAN ([io.panelassistant.android.http.listenerHost]): off the LAN it binds loopback
 * alone, and a LAN address would not load.
 */
internal fun panelSignInHost(listenerOffLan: Boolean, lanIpv4: String?): String =
    lanIpv4?.trim()?.takeIf { !listenerOffLan && isIpv4Literal(it) } ?: PANEL_SIGN_IN_LOOPBACK_HOST

private fun isIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == 4 && parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
}

internal fun panelHaOAuthStartUrl(haUrl: String, httpPort: Int, panelHost: String = PANEL_SIGN_IN_LOOPBACK_HOST): String =
    "http://$panelHost:$httpPort/api/v1/ha/oauth/panel-start?ha_url=${URLEncoder.encode(haUrl.trim().trimEnd('/'), "UTF-8")}"

internal fun panelHaOAuthNavigationAllowed(
    configuredUrl: String,
    candidateUrl: String,
    httpPort: Int,
    panelHost: String = PANEL_SIGN_IN_LOOPBACK_HOST,
): Boolean = dashboardNavigationAllowed(configuredUrl, candidateUrl) || isPanelHaOAuthCallback(candidateUrl, httpPort, panelHost)

/** The panel's own callback, on the host the sign-in was started on (or loopback) and the current port. */
internal fun isPanelHaOAuthCallback(
    candidateUrl: String,
    httpPort: Int,
    panelHost: String = PANEL_SIGN_IN_LOOPBACK_HOST,
): Boolean = runCatching {
    val uri = URI(candidateUrl.trim())
    val scheme = uri.scheme?.lowercase() ?: return@runCatching false
    val host = uri.host?.lowercase() ?: return@runCatching false
    scheme == "http" &&
        host in setOf(PANEL_SIGN_IN_LOOPBACK_HOST, "localhost", panelHost.lowercase()) &&
        uri.port == httpPort &&
        uri.path == HA_OAUTH_CALLBACK_PATH
}.getOrDefault(false)
