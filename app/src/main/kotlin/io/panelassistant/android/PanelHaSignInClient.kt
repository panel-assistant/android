package io.panelassistant.android

import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The client of the bare on-panel Home Assistant sign-in WebView: it keeps the page to the OAuth flow for
 * [haUrl], hands over once the callback reports success, and retries a failed page load quietly before
 * giving the panel's verdict after [LOAD_RETRIES_MAX] failures in a row.
 */
internal class PanelHaSignInClient(private val haUrl: String, private val httpPort: Int, private val host: Host) : WebViewClient() {
    interface Host {
        /** Whether [view] is still the activity's current sign-in WebView. */
        fun isCurrent(view: WebView): Boolean

        /** Load the sign-in start page into [view] again after [delayMs], if it is still current then. */
        fun retryLater(view: WebView, delayMs: Long)

        /** The sign-in page keeps failing to load: the panel's "sign-in page unavailable" screen. */
        fun showUnreachable()

        /** Home Assistant's callback reported the sign-in complete. */
        fun onSignedIn(view: WebView)
    }

    private var loadRetries = 0

    /** The current main frame failed. WebView then finishes loading its own error page in its place, so a
     *  finished page counts as working only when no error came first. */
    private var loadFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!host.isCurrent(view)) return true
        return !panelHaOAuthNavigationAllowed(haUrl, request.url.toString(), httpPort)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!host.isCurrent(view)) return
        // A page that loaded restores the budget: it counts failures in a row, not every hiccup of a long
        // sign-in, whose verdict would throw away what the person had typed.
        if (!loadFailed) loadRetries = 0
        loadFailed = false
        if (!isPanelHaOAuthCallback(url, httpPort)) return
        view.evaluateJavascript("document.body&&document.body.dataset?document.body.dataset.haOauthStatus:\"\"") { result ->
            if (host.isCurrent(view) && result?.trim('"') == "success") host.onSignedIn(view)
        }
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
    }
}
