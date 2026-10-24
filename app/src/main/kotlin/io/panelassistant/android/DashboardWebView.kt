package io.panelassistant.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.panelassistant.android.control.BottomSwipeDetector
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.EntityLearningProtocol
import io.panelassistant.android.dashboard.InjectionScript
import io.panelassistant.android.util.DashboardTheme

private const val TAG = DashboardActivity.TAG

/**
 * A new dashboard WebView with its settings and every document-start script, installed in the order the
 * frontend needs them, and the frontend bridge's listeners attached. [DashboardActivity] adds its own
 * clients and loads the page. Throws [EntityFilterInterceptorUnavailable] or [ExternalV2BridgeUnavailable],
 * after destroying the view, when the renderer must not open.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createDashboardWebView(
    activity: Activity,
    config: Config,
    activityOwner: Long,
    /** The theme policy baked into this WebView, recorded at the point it is baked. */
    onThemeSignature: (String) -> Unit,
    entityGate: DashboardEntityGate,
    bridge: DashboardFrontendBridge,
): WebView = WebView(
    // CSS media queries (including HA's loading screen) do not use the JavaScript matchMedia shim.
    // Give the provider the panel's scheme before it can paint; Follow keeps the activity's theme.
    when (DashboardTheme.forcedDark(config.dashboardThemeEffective)) {
        false -> android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_NoActionBar)
        true -> android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_NoActionBar)
        null -> activity
    },
).apply {
    val themeSignature = config.dashboardThemeEffective
    val forcedThemeDark = DashboardTheme.forcedDark(themeSignature)
    val documentStartOrigins = dashboardDocumentStartOrigins(config.haEffectiveUrl, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowContentAccess = false
    settings.allowFileAccess = false
    // Guard against a WebView bug that collapses fonts to unreadably small on some engines — the
    // HA Companion carries the same workaround (their PR #3353). 5px floors it without affecting
    // normal dashboard type.
    settings.minimumFontSize = 5
    // Camera cards / live feeds must start without a tap — the default (require user gesture) leaves
    // every stream paused on a touchless panel. (Not media-file playback, which stays out of scope.)
    settings.mediaPlaybackRequiresUserGesture = false
    setBackgroundColor(if (forcedThemeDark == false) android.graphics.Color.rgb(250, 250, 250) else DashboardActivity.BG_DARK)
    runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                InjectionScript.lifecycleNoticeJs(
                    haLifecycleNoticeState(
                        io.panelassistant.android.sensors.HaLifecycleRuntime.snapshot(),
                        RendererAdmissionRuntime.current()?.takeIf { it.owner == activityOwner },
                    ) != null,
                ),
                documentStartOrigins,
            )
        }
    }.onFailure { Log.w(TAG, "Home Assistant notice coordination unavailable", it) }
    // No overscroll stretch/glow: a wall panel rarely scrolls, and the bounce looks out of place.
    overScrollMode = View.OVER_SCROLL_NEVER
    // Page zoom to match the HA Companion's default sizing (it scales by device density); pinch
    // stays off (no builtInZoomControls) — the zoom is a deliberate per-panel value (see applyZoom).
    setInitialScale((resources.displayMetrics.density * config.dashboardZoom).toInt())
    activity.applyDashboardForceDark(this, forcedThemeDark)
    runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                InjectionScript.rejectionLoggingJs(),
                documentStartOrigins,
            )
        } else {
            Log.w(TAG, "no document-start support — dashboard rejection logging unavailable")
        }
    }.onFailure { Log.w(TAG, "dashboard rejection logging unavailable", it) }
    // The colour-scheme policy runs on every panel and every Android version, and the signature is
    // recorded HERE rather than at the call site because this is the WebView it is baked into:
    // onNewIntent compares that signature against config to decide whether to rebuild.
    // The EFFECTIVE policy, so Ambient arrives here as the Dark, Light or Follow it currently
    // resolves to and bakes exactly the script that choice always has. Published so the service
    // can tell whether a new ambient verdict still has to reach this WebView.
    onThemeSignature(themeSignature)
    BuiltinDashboard.setAppliedThemeSignature(activityOwner, themeSignature)
    runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                ExternalAuthProtocol.dashboardThemePolicyJs(forcedThemeDark),
                documentStartOrigins,
            )
        } else if (forcedThemeDark != null) {
            // Never silently: without document-start the policy cannot reach the page before Home
            // Assistant reads its own store, so forcing would simply appear not to work.
            Log.w(TAG, "no document-start support — dashboard theme policy cannot be applied")
        }
    }
    // Seed the dashboard's DEFAULT colour scheme through HA's own per-device theme store, before
    // any page script runs — only when the panel has no system dark mode (the Display-card toggle
    // population) and only if the user hasn't picked a theme in HA (see selectedThemeJs).
    //
    // Suppressed while a policy is forcing: both write the same field, and dark_mode's seed would
    // be a second authority over it. Under Follow this is byte-identical to before, which is what
    // leaves dark_mode's meaning unchanged.
    if (android.os.Build.VERSION.SDK_INT < 29 && forcedThemeDark == null) runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                ExternalAuthProtocol.selectedThemeJs(config.darkMode, onlyIfAbsent = true),
                documentStartOrigins,
            )
        }
    }
    // Force panel-appropriate HA frontend prefs on this WebView's FIRST run: hide the sidebar,
    // never suspend the websocket when idle, no haptics. HA's defaults are wrong for a wall panel
    // and don't carry over from the Companion, and most users don't know these settings exist —
    // so seed them once (self-gated by a localStorage sentinel), then leave them user-changeable.
    runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                ExternalAuthProtocol.panelDefaultsJs(),
                documentStartOrigins,
            )
        }
    }
    entityGate.installDocumentStartFilter(this, config, documentStartOrigins)
    if (config.dashboardEntityLearningEnabled) runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                EntityLearningProtocol.documentStartScript(
                    config.haEffectiveUrl,
                    documentStartOrigins,
                    BuildConfig.FEATURE_COSTS_ENABLED,
                ),
                documentStartOrigins,
            )
        }
    }.onFailure { Log.w(TAG, "entity-learning access observer unavailable", it) }
    // Last on purpose: it must be the outermost WebSocket wrapper to see every outbound theme save
    // (see dashboardThemeSocketJs). Under Follow nothing is installed.
    if (forcedThemeDark != null) runCatching {
        if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                ExternalAuthProtocol.dashboardThemeSocketJs(forcedThemeDark, config.haEffectiveUrl, documentStartOrigins),
                documentStartOrigins,
            )
        }
    }.onFailure { Log.w(TAG, "dashboard theme cannot beat a theme saved in Home Assistant", it) }
    // The HA frontend relies on cookies (incl. third-party for some integrations).
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    try {
        bridge.installListeners(this, config)
    } catch (error: Throwable) {
        runCatching { destroy() }
        throw ExternalV2BridgeUnavailable(error)
    }
}

/**
 * The dashboard's DEFAULT colour scheme, by Android tier (a theme picked inside HA always wins —
 * WEB_THEME_DARKENING_ONLY never darkens a page that pinned a light theme):
 *  - 13+: nothing to do — the WebView derives prefers-color-scheme from the app theme, which is
 *    FOLLOW_SYSTEM, so the OS setting drives it live (uiMode arrives via onConfigurationChanged).
 *  - 10..12: a system dark/light setting exists but old WebViews don't follow the app theme — mirror
 *    the CURRENT system uiMode into the force-dark flag (re-applied on every uiMode change below).
 *  - 9-: no system setting exists — the panel's `dark_mode` config (Display card) decides.
 */
internal fun Context.applyDashboardForceDark(
    w: WebView,
    forcedDark: Boolean? = DashboardTheme.forcedDark(Config(this).dashboardThemeEffective),
) = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 33) return@runCatching
    val dark = forcedDark ?: if (android.os.Build.VERSION.SDK_INT >= 29) {
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    } else {
        Config(this).darkMode
    }
    if (webViewFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
        @Suppress("DEPRECATION")
        androidx.webkit.WebSettingsCompat.setForceDark(
            w.settings,
            if (dark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF,
        )
    }
    if (dark && webViewFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK_STRATEGY)) {
        @Suppress("DEPRECATION")
        androidx.webkit.WebSettingsCompat.setForceDarkStrategy(
            w.settings, androidx.webkit.WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY,
        )
    }
}

internal fun shouldEnableWebViewDebugging(networkAdbEnabled: Boolean, hardenedSecurityEnabled: Boolean): Boolean =
    networkAdbEnabled && !hardenedSecurityEnabled

/**
 * Which of the capture resources a page asked for may be handed to it.
 *
 * Camera capture is refused outright: one owner holds the device and every consumer is its
 * subscriber, so a page's `getUserMedia()` would be a second, independent owner that the LIMITED
 * HALs on these panels cannot share with the session. Declaring CAMERA for the service must never
 * arm that branch by accident.
 *
 * Microphone capture needs three things, and the Android permission is the least of them. It needs
 * an explicit opt-in, because provisioning grants `RECORD_AUDIO` to every panel and a granted
 * permission is therefore not a decision anybody made about this page. It needs an idle shared
 * source, because the panel has one microphone and one capture client, so handing the page a
 * recorder while ha-paneld holds a lease takes the microphone from one of them silently. And it
 * needs the permission itself. A resource this build does not understand is never granted.
 */
internal fun webViewCaptureGrants(
    requested: Array<String>,
    permissionHeld: (String) -> Boolean,
    microphoneIdle: () -> Boolean,
    webViewCaptureAllowed: () -> Boolean,
): Array<String> = requested.filter { resource ->
    when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
            webViewCaptureAllowed() && permissionHeld(Manifest.permission.RECORD_AUDIO) && microphoneIdle()
        else -> false
    }
}.toTypedArray()

/**
 * Pull-to-refresh that only arms for a drag beginning at the very top edge of the screen — a pull in
 * from the bezel. SwipeRefreshLayout's stock gate asks whether the child can scroll up, but the HA
 * frontend scrolls *inside* the page, so the WebView always reports "at the top" and every downward
 * drag on the dashboard (scrolling a view, dragging a slider card) would start the gesture (#29).
 * Gating on the gesture's origin makes scroll position irrelevant: a drag whose initial touch lands
 * below the edge band is never intercepted, so the content underneath sees it untouched.
 */
internal class EdgePullRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    private val edgePx = (EDGE_BAND_DP * resources.displayMetrics.density).toInt()
    private var armed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) armed = ev.y <= edgePx
        return armed && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean = armed && super.onTouchEvent(ev)

    private companion object {
        // Wide enough that a quick bezel swipe's first sampled touch still lands inside it; narrow
        // enough that a drag starting on dashboard content (HA's header alone is ~56dp) never does.
        const val EDGE_BAND_DP = 24
    }
}

/**
 * Root container that detects the soft-navbar swipe-reveal gesture IN-PROCESS while the built-in
 * renderer is foreground — replacing [io.panelassistant.android.control.NavbarController]'s bottom
 * overlay strip, which consumed every touch in its 48dp band and made the dashboard's bottom edge
 * tap-dead (root `input tap` re-injection) or tap-dropping (no root). Qualification is by the gesture's
 * ORIGIN in a bottom edge band, never by content-scroll state — the same principle as
 * [EdgePullRefreshLayout] at the top edge. A gesture that never crosses the upward-travel threshold is
 * NEVER intercepted, so taps and scrolls reach the WebView untouched with zero latency.
 */
internal class BottomSwipeFrame(
    context: Context,
    private val enabled: () -> Boolean,
    private val onSwipeUp: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val detector = BottomSwipeDetector(
        bandPx = BottomSwipeDetector.BAND_DP * density,
        minTravelPx = BottomSwipeDetector.MIN_TRAVEL_DP * density,
    )
    private var stolen = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stolen = false; detector.onDown(ev.x, ev.y, height, enabled()) }
            MotionEvent.ACTION_POINTER_DOWN -> detector.abort() // a pinch on a bottom-band card is not a reveal
            MotionEvent.ACTION_MOVE -> if (detector.onMove(ev.x, ev.y)) {
                stolen = true
                onSwipeUp()
                return true // steal: the framework CANCELs the child chain (page sees a normal touchcancel)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> detector.abort()
        }
        return false
    }

    /** After a steal, consume the remainder of the gesture so the stream stays with us. */
    override fun onTouchEvent(ev: MotionEvent): Boolean = stolen

    /** WebView content (slider/map cards handling their own drag) calls this to stop us intercepting;
     *  honour it for everything EXCEPT an edge-origin gesture we're still tracking, so page content can't
     *  defeat the reveal (the DrawerLayout edge-swipe precedent). */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && detector.tracking) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
