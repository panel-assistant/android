package io.github.maxlyth.hapaneld

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import io.github.maxlyth.hapaneld.control.PanelTouchObserver
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.maxlyth.hapaneld.audio.MicrophoneAdmission
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.maxlyth.hapaneld.control.BottomSwipeDetector
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityFilterTelemetry
import io.github.maxlyth.hapaneld.dashboard.InjectionScript
import io.github.maxlyth.hapaneld.dashboard.shouldInstallDashboardTrafficObserver
import io.github.maxlyth.hapaneld.dashboard.AutoScopeVerification
import io.github.maxlyth.hapaneld.dashboard.shouldHoldRendererForEntityBootstrap
import io.github.maxlyth.hapaneld.dashboard.EntityLearningProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityLearningRuntime
import io.github.maxlyth.hapaneld.dashboard.HomeDashboardLaunchCache
import io.github.maxlyth.hapaneld.dashboard.EntityBootstrapProblem
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.DashboardTheme
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.LocalAdminEndpoint
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import io.github.maxlyth.hapaneld.util.scannableHost
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.LinkedHashMap

internal fun shouldRouteDashboardHomeToAdmin(
    configuredLauncherPackage: String,
    ownPackage: String,
    action: String?,
    categories: Set<String>?,
): Boolean = configuredLauncherPackage == ownPackage &&
    action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true

internal enum class EntityFilterFailureDisposition { HOLD_NATIVE, ALLOW_DIRECT }

/** Automatic learning must never turn an interceptor failure into a full, unfiltered HA stream. */
internal fun entityFilterFailureDisposition(
    automaticLearningEnabled: Boolean,
    filterConfigured: Boolean,
): EntityFilterFailureDisposition =
    if (automaticLearningEnabled && filterConfigured) EntityFilterFailureDisposition.HOLD_NATIVE
    else EntityFilterFailureDisposition.ALLOW_DIRECT

internal fun invalidEntityFilterFailureDisposition(
    signature: String,
    automaticLearningEnabled: Boolean,
    filterConfigured: Boolean,
): EntityFilterFailureDisposition =
    if (signature.startsWith("invalid")) {
        entityFilterFailureDisposition(automaticLearningEnabled, filterConfigured)
    } else {
        EntityFilterFailureDisposition.ALLOW_DIRECT
    }

internal fun deferReadyEntityBootstrapUntilWake(screenAwake: Boolean): Boolean = !screenAwake

internal fun shouldKeepBuiltInRendererScreenOn(preventIdleDim: Boolean): Boolean = preventIdleDim

internal fun entityFilterAttentionDetail(blockingIssues: Int, entitiesAddress: String? = null): String {
    require(blockingIssues > 0) { "blocking issue count must be positive" }
    val remote = entitiesAddress?.takeIf(String::isNotBlank)
        ?.let { " The same choices are available from another device at $it." }
        .orEmpty()
    return "Nothing is wrong with Home Assistant. The panel needs an answer about safety checks " +
        "found while reading your entities before it can open the dashboard. " +
        "Number requiring review: $blockingIssues.$remote"
}

private data class EntityFilterNativeHold(val error: String, val detail: String)

private class EntityFilterInterceptorUnavailable(cause: Throwable) : RuntimeException(cause)

private class ExternalV2BridgeUnavailable(cause: Throwable) : RuntimeException(cause)

private data class PendingV2Auth(
    val config: Config,
    val generation: Long,
    val session: ExternalBusController.Session,
    val payload: String,
    val force: Boolean,
)

private data class V2BridgeDocument(
    val config: Config,
    val generation: Long,
    val session: ExternalBusController.Session,
    val filterLease: EntityFilterTelemetry.Lease?,
)

internal data class HomeDashboardResolutionOwner(
    val authOwner: HaAuthOwner,
    val configuredPath: String,
)

/** Owns the only scan-independent dashboard-list result allowed to admit the renderer. */
internal class HomeDashboardResolutionAttemptGate {
    data class Ticket(val epoch: Long, val owner: HomeDashboardResolutionOwner)

    @Volatile private var current: Ticket? = null
    private var nextEpoch = 0L

    @Synchronized fun start(owner: HomeDashboardResolutionOwner): Ticket =
        Ticket(++nextEpoch, owner).also { current = it }

    fun owns(ticket: Ticket, currentOwner: HomeDashboardResolutionOwner): Boolean =
        current == ticket && ticket.owner == currentOwner

    @Synchronized fun invalidate() { current = null }
}

internal data class OwnedHomeDashboardResolution(
    val owner: HomeDashboardResolutionOwner,
    val resolution: EntityLearningProtocol.HomeDashboardResolution,
    /** False while the value is the persisted launch cache — already rendering, awaiting the live
     *  answer that confirms, corrects or (on a confirmed empty list) replaces it. */
    val confirmed: Boolean = true,
)

/**
 * The home dashboard path [resolution] holds for [owner], or null while there is none yet: nothing
 * resolved, resolution invalidated for a re-admission, a setting or credential change that has not
 * reached the activity's rebuild, or a confirmed list with no legal dashboard. Null means "not yet",
 * never an error; each caller decides what waiting looks like.
 */
internal fun ownedHomeDashboardPath(
    resolution: OwnedHomeDashboardResolution?,
    owner: HomeDashboardResolutionOwner,
): String? = resolution?.takeIf { it.owner == owner }?.resolution?.path

internal class EntityFilterRetryPolicy(
    private val delaysMs: LongArray = longArrayOf(30_000L, 120_000L, 600_000L),
) {
    private var attempts = 0

    /** A dark panel does no provider/WebView work and does not spend its finite retry budget. */
    fun nextDelay(screenAwake: Boolean): Long? =
        if (!screenAwake) null else delaysMs.getOrNull(attempts)

    fun recordAttempt() {
        if (attempts < delaysMs.size) attempts++
    }

    fun reset() { attempts = 0 }
}

/**
 * Built-in dashboard renderer (experimental): a full-screen WebView onto the configured Home
 * Assistant URL, signed in through the frontend's documented external-auth bridge — the same
 * `?external_auth=1` + `window.externalAppV2` contract the HA Companion app uses. Requires `ha_url`
 * and `ha_token` (a long-lived access token set at provisioning); with either blank this activity
 * shows nothing useful, so entry points should gate on [Config.haUrl].
 *
 * Deliberately NOT a Companion replacement: no notifications, no sensors, no media/file-chooser
 * support — users who need those run the Companion app instead. The renderer's whole job is to
 * paint the dashboard and stay alive: a killed renderer process is rebuilt in place rather than
 * crashing the app (the page-level half of the never-blank guarantee).
 */
class DashboardActivity : AppCompatActivity() {

    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private lateinit var activityConfig: Config
    private var web: WebView? = null
    private var swipe: SwipeRefreshLayout? = null
    private var root: FrameLayout? = null                       // holds the swipe layout + fullscreen video
    private var lifecycleBar: HaLifecycleBar? = null            // native HA outage bar, lives inside `root`
    private var networkChip: HaNetworkChip? = null              // native "HA network slow" chip, same root

    /**
     * Marshals to the UI thread because the state machine is driven from the service's IO scope.
     * Registered for the activity's whole life, not just while a WebView exists, so the bar is correct
     * the moment one is built.
     */
    /**
     * A poke, not a payload: the bar re-reads the one source of truth. Marshals to the UI thread because
     * the state machine is driven from the service's IO scope.
     */
    private val haLifecycleListener: () -> Unit = {
        main.post { if (!destroyed) redrawLifecycleBar() }
    }

    private fun redrawLifecycleBar() {
        // ONE snapshot read: state, source and remaining lifetime from the same instant, or null when
        // no service owns lifecycle tracking — which hides the bar, so a card cannot outlive the
        // service whose state it was rendering.
        lifecycleBar?.update(io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime.snapshot())
        // The network-path chip answers the same poke from ITS one owner: one atomic snapshot, null
        // (hidden) when no service owns the monitor or no socket is held.
        networkChip?.update(io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime.snapshot())
    }

    /** Drop the bar and chip with their container. Every content-view swap must reach here, or they outlive their root. */
    private fun detachLifecycleBar() {
        lifecycleBar?.detach()
        lifecycleBar = null
        networkChip?.detach()
        networkChip = null
    }
    private var entityFilterSignature = "disabled"
    // The colour-scheme policy baked into the live WebView's document-start script. Document-start
    // scripts cannot be replaced in an existing WebView, so a policy change rebuilds it (below).
    private var dashboardThemeSignature = DashboardTheme.DEFAULT
    private var entityFilterLease: EntityFilterTelemetry.Lease? = null
    private var entityFilterNativeHold: EntityFilterNativeHold? = null
    private var customView: View? = null                        // active onShowCustomView (fullscreen) view
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // --- long-run reliability state (all touched on the main thread only) ---
    private val main = Handler(Looper.getMainLooper())
    // Camera trial: the permission dialog may only be raised from an observably resumed activity, and
    // AndroidX reports RESUMED only after onResume returns; this gate holds a signal that lands too
    // early or while paused and delivers it at the lifecycle's own ON_RESUME.
    private val cameraPromptDelivery = io.github.maxlyth.hapaneld.camera.CameraPromptDelivery { requestCameraPermissionIfNeeded() }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rendererGate = RendererGenerationGate()
    private val wakeMediaRecovery = WakeMediaRecoveryGate()
    private val entityFilterRetryPolicy = EntityFilterRetryPolicy()
    /** When the pending entity-filter retry is due, so a redraw reschedules it rather than restarting it. */
    private var entityFilterRetryDueAtMs = 0L
    private var rendererGeneration = 0L
    private var activityOwner = 0L
    private var conn: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRecovery: NetworkRecoveryGate? = null
    // Set in onDestroy. Bridge/network callbacks marshal onto the main thread, so one of their posts can
    // land AFTER onDestroy's removeCallbacksAndMessages and re-arm the self-perpetuating watchdog on a
    // dead activity. Every posted handler checks this first so nothing runs (or re-schedules) post-destroy.
    @Volatile private var destroyed = false
    private val rendererPowerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "prevent_idle_dim") {
            runOnUiThread {
                if (!destroyed && ::activityConfig.isInitialized) applyRendererScreenPolicy()
            }
        }
        // A persisted theme change has to reach whatever is on the display NOW. Without this only a
        // SYSTEM uiMode change redrew anything, so a panel switched between light and dark from its own
        // settings left an installed compatibility or recovery screen on the old palette and artwork —
        // and the two WebView-drawn pages bake their colours into static HTML, so they stayed stale
        // even where the system change did redraw the native ones.
        if (key == "dashboard_theme_dark" || key == "dark_mode" || key == "dashboard_theme") {
            runOnUiThread { if (!destroyed) convergeStatusTheme() }
        }
        // Camera trial: the runtime permission is requested only once the setting is on,
        // and only as a direct result of that switch turning on — never before, never as a side effect.
        // camera_enabled needs no branch here: the service's camera owner republishes
        // CameraPermissionPrompt on that change, asynchronously on its own lane, and the prompt's
        // listener — registered below while this activity is resumed — is what raises the dialog.
        // Asking on the raw preference change raced that publication and could see nothing to ask.
    }

    /** Redraw every ha-paneld status surface that is currently rendered, native or WebView-drawn. */
    private fun convergeStatusTheme() {
        statusSurface = null
        val redraw = statusRerender
        if (redraw != null) {
            root = null
            redraw()
            return
        }
        // No native status screen is installed, so the only ha-paneld-owned surfaces that can be on
        // screen are the two pages drawn into the dashboard WebView. Re-issue whichever is showing.
        if (authLatched) renderAuthLatchPage() else if (interstitialShown) reissueReconnecting()
    }
    // Stored so onDestroy can clear it by identity — an old instance destroyed after a new one's onCreate
    // must not wipe the new instance's registration (see BuiltinDashboard.clearScreenListener).
    private val screenListener: (Boolean) -> Unit = { awake -> runOnUiThread { onScreenChanged(awake) } }
    // Frontend-handshake watchdog: the page "loading" (onPageFinished) is NOT health — the frontend JS
    // app must post `connection-status: connected` on the external bus. Until it does, we retry with
    // backoff (a panel has no buttons to press). This catches the "loaded onto a blank/dead frontend"
    // class that onPageFinished and the crash budget both miss. The watchdog runs ONLY while the screen is
    // awake: a screen-off pauses JS timers, so a paused WebView could never complete the handshake and the
    // watchdog would just reload-loop all night behind a dark panel.
    /**
     * Whether the Home Assistant frontend is connected right now.
     *
     * The setter publishes to [RendererAdmissionRuntime] so `/api/v1/status`, `/api/v1/diag` and the
     * info card all learn it. It is a SETTER rather than a call beside each assignment on purpose:
     * five sites write this field (connect, disconnect, page start, teardown, destroy) and a
     * hand-maintained list of them would be one edit away from a surface that claims a dashboard is
     * up after it went down. The property initializer assigns the backing field directly, so the
     * pre-`onCreate` value never publishes against an unacquired lease.
     */
    private var themeObservationEpoch = 0L
    private var frontendConnected = false
        set(value) {
            // A pending evaluateJavascript callback belongs to the connection that started it. A
            // disconnect, page replacement or teardown must invalidate that callback before the
            // runtime clears its effective-theme observation, or the stale result can republish it.
            if (!value) themeObservationEpoch++
            field = value
            RendererAdmissionRuntime.setFrontendConnected(activityOwner, value)
        }
    private val retryPolicy = DashboardRetryPolicy()
    private var clearedThisLoad = false
    private val watchdog = Runnable { onWatchdogTimeout() }
    // After a screen-off reload (memory shed), let the fresh page load with timers LIVE for a settle
    // window, then freeze — so we never reload into a frozen WebView, but also never leave it churning
    // behind a dark screen forever. Fires only if the frontend hasn't already connected (which freezes early).
    private val darkSettle = Runnable {
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !screenAwake) web?.pauseTimers()
    }
    // Time-based memory ceiling. onTrimMemory's RUNNING_* levels are deprecated + never delivered on
    // Android 14 (the fleet), so a periodic check is the portable ceiling: reload invisibly at the next
    // screen-off past RELOAD_INTERVAL_MS, or force a (brief, visible) reload past RELOAD_HARD_MS so a
    // never-sleeping panel still sheds accreted WebView memory ~daily.
    private val periodicCheck = Runnable { onPeriodicCheck() }
    private var lastFullLoadAt = 0L
    private var reloadPending = false
    private var screenAwake = true
    // One bounded owner for typed app→frontend commands and their per-document result correlation.
    private val externalBus = ExternalBusController()
    private var externalBusSession: ExternalBusController.Session? = null
    private var v2BridgeDocument: V2BridgeDocument? = null
    private var v2ListenerView: WebView? = null
    private var expectedPageStartUrl: String? = null
    private val busTimeouts = LinkedHashMap<Int, Runnable>()
    private var compatibilityJob: Job? = null
    private var compatibilityCheckingOwner: DashboardV2CompatibilityOwner? = null
    private var compatibilityReadyUrl: String? = null
    private var homeDashboardJob: Job? = null
    private var homeDashboardCheckingOwner: HomeDashboardResolutionOwner? = null
    private var homeDashboardResolution: OwnedHomeDashboardResolution? = null
    // Deliberate-navigation generation (see noteDeliberateDashboardNavigation) and the generation at
    // which the provisional cached page was chosen; correction applies only while they still match.
    private var dashboardNavigationEpoch = 0L
    private var provisionalHomeDashboardEpoch: Long? = null
    // Full dashboard route the app itself last aimed the page at. Frontend-observed navigation away
    // from it is the user moving, and outranks a pending cache correction.
    private var appNavigatedDashboardRoute: String? = null
    private val homeDashboardAttempts = HomeDashboardResolutionAttemptGate()
    // One automatic-recovery owner for EVERY blocked admission screen. A wall panel has nobody
    // standing at it, so a blocked screen without a timer stays blocked after its cause clears —
    // recoverable only by a physical tap or an app restart. The timer and the manual Retry button run
    // the same sequence; the button also resets the back-off because a present human should not wait
    // out a timer.
    private val admissionRetryPolicy = AdmissionRetryPolicy()
    // The retry deadline and the visible countdown are owned separately on purpose: the countdown may
    // legitimately stop while the retry must not. Uptime is the Handler's own clock, so a deep sleep
    // freezes the displayed figure and the pending callback together.
    private val admissionCountdown = AdmissionCountdownOwner { SystemClock.uptimeMillis() }
    private var admissionCountdownView: TextView? = null
    private val admissionRetry = Runnable {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return@Runnable
        Log.i(TAG, "renderer admission auto-retry firing")
        // A provisional cached dashboard is already rendering: retry the live resolution behind it
        // without invalidating what the user is watching. Everything else keeps the original
        // clear-and-rebuild retry.
        if (homeDashboardResolution?.confirmed == false) {
            buildAndLoad(Config(this))
        } else {
            retryAdmission(resetBackoff = false)
        }
    }
    private val admissionCountdownTick = Runnable { onAdmissionCountdownTick() }

    /** Endpoint the on-panel Home Assistant sign-in is currently showing, so a relaunch does not restart it. */
    private var signInShownForUrl: String? = null

    /** Quiet retries before the sign-in surface declares itself unavailable — its start page is served
     *  by the panel's own HTTP server, which restarts briefly during config/MQTT reconfigures. */
    private var signInLoadRetries = 0

    /**
     * Every DELIBERATE dashboard restart announces itself on the panel, so an
     * on-purpose reset can never be mistaken for a crash — otherwise the built-in renderer earns a
     * reputation for unreliability one report at a time. Native (no WebView dependency), transient,
     * never blocks the reload, names ha-paneld as the actor, and never shown for genuine crashes:
     * the distinction is the point. A full-rebuild overlay was tried and rejected on
     * hardware — it vanished with the container the moment the rebuild swapped views and its
     * full-bleed layout read no better than the toast.
     *
     * First entity-filter learning is a burst of restarts, not one, and a toast per rebuild read on
     * hardware as a crashing dashboard. [BuiltinDashboard.restartAnnouncements] says the burst once (the
     * native bootstrap hold, or the first learning toast) and keeps the rest quiet; see
     * [io.github.maxlyth.hapaneld.control.DeliberateRestartAnnouncements].
     */
    private fun announceDeliberateRestart(
        reason: String,
        learning: Boolean = reason == BuiltinDashboard.LEARNING_RELOAD_REASON,
    ) {
        if (!BuiltinDashboard.restartAnnouncements.shouldAnnounce(
                learning = learning,
                dashboardShown = web != null,
                nowMs = SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "learning restart not announced again (${if (web == null) "no dashboard shown" else "learning window open"})")
            return
        }
        val shownReason = when (reason) {
            BuiltinDashboard.LEARNING_RELOAD_REASON, "updating the entity filter" ->
                getString(R.string.optimizing_entities_restart)
            "applying your settings", "clearing the dashboard’s stored data" ->
                getString(R.string.applying_changes)
            else -> reason.ifBlank { getString(R.string.applying_changes) }
        }
        runCatching {
            android.widget.Toast.makeText(
                this,
                getString(R.string.dashboard_restart_announcement, shownReason),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    private val compatibilityAttempts = DashboardV2AttemptGate()
    private val v2Handshake = V2HandshakeGate(V2_MISSING_RELOAD_LIMIT)
    // V2 callbacks arrive on the UI thread. Keep at most one blocking auth resolution in flight and
    // one latest request waiting behind it; repeated frontend requests cannot grow coroutine retention.
    private val authQueue = BoundedAuthQueue<PendingV2Auth>(
        sameOwner = { left, right -> left.session == right.session },
        forced = PendingV2Auth::force,
    )
    // Auth-failure latch. A terminal login-settings rejection (refresh token/client id, repeated
    // auth-invalid from the frontend) must not become an infinite reload loop on an unattended panel —
    // no unchanged retry can repair it. Latch: stop the retry machinery and show a clear on-panel
    // message naming the fix. Unlatched by a new load (MQTT reload/navigate, config change) or a
    // successful connect. Transient failures (HA down) never count toward the latch.
    private var authInvalids = 0
    private var refreshRejects = 0
    private var authLatched = false
    // Idle return-to-home: after N minutes with no touch (configurable, 0 = off), navigate the frontend
    // back to the home dashboard via the bus — cheaper than a reload, bounds history growth from casual
    // navigation, and snaps a wandering kiosk back where it belongs.
    private var lastTouchAt = 0L
    private val idleCheck = Runnable { onIdleCheck() }
    private val idleUnresolvedNotice = DashboardIdleReturnPolicy.UnresolvedNotice()
    // Last light pull-to-refresh — a second pull inside the window escalates to a full hard reload.
    private var lastLightRefreshAt = 0L
    // True while the branded "Reconnecting…" page has replaced a failed main-frame load. Every reload
    // path must then loadUrl() the real dashboard rather than reload() (which would reload the
    // interstitial itself). Cleared on any real load or connect.
    private var interstitialShown = false
    // The main-frame document the dashboard WebView last committed (onPageStarted), or null before its
    // first commit. A retry decides reload() versus a fresh load from this; see retryNeedsFreshLoad.
    private var committedPageUrl: String? = null
    private var waitingStatus: TextView? = null
    private var waitingStage: TextView? = null
    private var waitingProgress: ProgressBar? = null
    private var waitingEstimateMs = 0L
    private var waitingEstimateLearned = false
    private var entityBootstrapBlockedCount = -1
    private var entityBootstrapMilestoneView: TextView? = null
    /** Owns every deadline of the hold: resync ladder, give-up, and the decision-hold probe cadence. */
    private val entityBootstrapWatchdog = EntityBootstrapWatchdog(
        retryMs = BOOTSTRAP_WATCHDOG_RETRY_MS,
        retryCeilingMs = BOOTSTRAP_WATCHDOG_RETRY_CEILING_MS,
        problemMs = BOOTSTRAP_WATCHDOG_PROBLEM_MS,
        probeMs = BOOTSTRAP_WATCHDOG_PROBE_MS,
        probeCeilingMs = BOOTSTRAP_WATCHDOG_PROBE_CEILING_MS,
    )
    /** The decision hold announces itself in the log once, not once per poll. */
    private var entityBootstrapDecisionLogged = false
    private var entityBootstrapProblem: EntityBootstrapProblem? = null
    private var waitingStartedAt = 0L
    private val waitingTick = object : Runnable {
        override fun run() {
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || waitingStatus == null) return
            val elapsed = SystemClock.elapsedRealtime() - waitingStartedAt
            val expected = ((waitingEstimateMs + 2_500L) / 5_000L) * 5L
            waitingStatus?.text = if (waitingEstimateLearned) {
                getString(R.string.elapsed_usually, elapsed / 1_000L, expected)
            } else {
                getString(R.string.elapsed_learning, elapsed / 1_000L)
            }
            waitingProgress?.progress = networkWaitProgress(elapsed, waitingEstimateMs)
            waitingStage?.text = localizedStartupNetworkStage(startupNetworkSnapshot())
            main.postDelayed(this, 1_000L)
        }
    }
    // A fresh automatic learner has no safe document-start allow-list yet. Keep a native screen in
    // front instead of ever loading HA unfiltered; this poll is a backstop for a missed/blocked service
    // relaunch when synchronization commits the first set.
    private val entityBootstrapCheck = object : Runnable {
        override fun run() {
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
            val config = Config(this@DashboardActivity)
            if (holdForEntityBootstrap(config)) {
                // The hold is structurally forbidden from being terminal. The enable-time sync has died
                // silently on hardware more than once, each time leaving a happy spinner over a dead
                // process until a human intervened. Two deadlines: at 30 seconds fire the same recovery
                // the manual sync endpoint provides; at 90 seconds stop impersonating progress and show
                // the problem screen, whose retry/disable buttons already exist.
                //
                // Firing the recovery once was not enough. The commonest reason the scan cannot answer is
                // that Home Assistant is not up yet — a power cut brings the panel back first — and a
                // one-shot retry means the panel is still holding, or already on the problem screen, long
                // after HA returned. Nothing else re-drives it: the periodic sync skips a freshly-synced
                // catalogue and skips again while the screen is awake, and no HA-reconnect trigger exists.
                // So keep retrying on a widening interval, capped, for as long as the hold lasts.
                //
                // Unless the scan HAS answered and a flagged rule is waiting on a person. A resync cannot
                // supply that decision, and one panel performed 794 of them over 2.75 days waiting for
                // somebody to walk up to it. There the watchdog asks Home Assistant, on a much wider
                // cadence, whether the dashboard itself changed, and otherwise stays quiet. The state
                // machine owns every clock; see EntityBootstrapWatchdog.
                val now = SystemClock.elapsedRealtime()
                val blocking = EntityLearningRuntime.blockingIssueCount()
                val problem = EntityLearningRuntime.bootstrapProblem()
                val action = entityBootstrapWatchdog.tick(
                    nowMs = now,
                    blockingIssues = blocking,
                    problem = problem,
                    syncRunning = EntityLearningRuntime.syncRunning(),
                )
                if (entityBootstrapWatchdog.heldOnDecision && !entityBootstrapDecisionLogged) {
                    entityBootstrapDecisionLogged = true
                    Log.i(
                        TAG,
                        "entity bootstrap held on a decision about $blocking flagged rule(s); catalogue resync " +
                            "suspended until the dashboard changes or the rule is answered",
                    )
                } else if (!entityBootstrapWatchdog.heldOnDecision) {
                    entityBootstrapDecisionLogged = false
                }
                when (action) {
                    EntityBootstrapWatchdogAction.RESYNC -> {
                        Log.w(
                            TAG,
                            "entity bootstrap held — watchdog resync ${entityBootstrapWatchdog.resyncs}",
                        )
                        EntityLearningRuntime.retryBootstrap()
                    }
                    EntityBootstrapWatchdogAction.PROBE -> {
                        Log.i(TAG, "entity bootstrap held on a decision — dashboard change probe ${entityBootstrapWatchdog.probes}")
                        EntityLearningRuntime.probeDashboardChange()
                    }
                    EntityBootstrapWatchdogAction.PRESENT_PROBLEM -> {
                        showWaitingForEntityBootstrap()
                        return
                    }
                    EntityBootstrapWatchdogAction.IDLE -> Unit
                }
                if (blocking != entityBootstrapBlockedCount || problem != entityBootstrapProblem) {
                    showWaitingForEntityBootstrap()
                    return
                }
                // Live milestone tick — the count climbing is the trust signal a spinner never was.
                entityBootstrapMilestoneView?.takeIf { it.isAttachedToWindow }?.let {
                    // Still on screen: the learning notice counts from when it was last seen, not first.
                    BuiltinDashboard.restartAnnouncements.learningNoticeShown(now)
                    val milestone = localizedBootstrapMilestone()
                    if (milestone.isNotBlank() && it.text != milestone) it.text = milestone
                }
                main.postDelayed(this, ENTITY_BOOTSTRAP_CHECK_MS)
                return
            }
            entityBootstrapWatchdog.reset()
            entityBootstrapDecisionLogged = false
            // A sync may finish after the panel went dark. Do not create a WebView whose timers have no
            // connection callback or dark-settle owner; the next poll after a real wake will build it.
            if (deferReadyEntityBootstrapUntilWake(screenAwake)) {
                main.postDelayed(this, ENTITY_BOOTSTRAP_CHECK_MS)
                return
            }
            entityFilterLease?.let(EntityFilterTelemetry::stop)
            entityFilterLease = null
            configureEntityFilter(config)
            buildAndLoad(config)
        }
    }
    /**
     * Waits for the setup wizard's entity-filter answer, then builds the renderer.
     *
     * Deliberately stays inside this activity rather than handing back to [MainActivity]: finishing here
     * would be picked up by the kiosk/watchdog return loop and relaunched, which would detect the same hold
     * and finish again — a churn loop on the never-strand path. Holding a static native screen costs nothing
     * and cannot loop.
     */
    private val entityFilterAnswerCheck = object : Runnable {
        override fun run() {
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
            val config = Config(this@DashboardActivity)
            if (entityFilterQuestionPending(
                    builtinRenderer = true,
                    haUrl = config.haUrl,
                    haToken = config.haToken,
                    haRefreshToken = config.haRefreshToken,
                    entityFilterAnswered = config.setupEntityFilterAnswered,
                    setupEverCompleted = config.setupEverCompleted,
                    entityFilterEnabled = config.dashboardEntityLearningEnabled,
                )
            ) {
                main.postDelayed(this, ENTITY_FILTER_ANSWER_CHECK_MS)
                return
            }
            buildAndLoad(config)
        }
    }
    private val entityFilterRetry = Runnable {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || !screenAwake || entityFilterNativeHold == null) return@Runnable
        entityFilterRetryDueAtMs = 0L
        entityFilterRetryPolicy.recordAttempt()
        retryEntityFilter(Config(this@DashboardActivity))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        supportActionBar?.hide()
        // DashboardActivity can be foregrounded directly by HOME restoration, the admin path, or a
        // privileged start. Always bootstrap the service here too so the local HTTP/MQTT surface is
        // alive even when MainActivity was bypassed.
        PaneldService.start(this)
        val config = Config(this)
        // Android 14's HOME role resolves at package granularity when one package exposes multiple HOME
        // activities: set-home-activity can report success for AdminLauncherActivity yet still resolve
        // DashboardActivity. Route an actual HOME intent according to the explicit Launcher app policy;
        // service/watchdog starts are component-explicit and therefore continue to foreground the dashboard.
        if (shouldRouteDashboardHomeToAdmin(config.launcherPackage, packageName, intent?.action, intent?.categories)) {
            Log.i(TAG, "HOME invoked with Panel admin selected — opening the admin launcher")
            fallbackToLauncher()
            return
        }
        activityOwner = BuiltinDashboard.acquireActivityOwner()
        if (!config.builtInRendererReady()) {
            // A configured URL with no credential is the one not-ready state that can fix itself: the
            // on-panel Home Assistant sign-in produces the missing token. Bouncing here instead is what
            // made first-run unfinishable from the panel — the sign-in screen lives past this gate, so
            // the gate was refusing entry to the only thing that could satisfy it.
            if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
                Log.i(TAG, "Home Assistant URL set without credentials — continuing to the on-panel sign-in")
            } else {
                // We're a HOME activity: never finish to a blank home. With no URL/auth to render, hand off
                // to ha-paneld's first-run QR/configure surface, not the admin launcher grid. A clean install
                // must be usable from the physical panel without ADB/debug handholding.
                Log.w(TAG, "built-in renderer not ready — opening the first-run configure surface instead")
                fallbackToFirstRunSurface()
                return
            }
        }
        activityConfig = config
        activityConfig.registerChangeListener(rendererPowerListener)
        // The service's camera owner publishes the prompt state on its own lane; the listener posts
        // to main (never inline) and the delivery gate defers to the lifecycle's ON_RESUME, which is
        // the first point AndroidX reports RESUMED. Registering fires at once if an ask is already due.
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> cameraPromptDelivery.onResumed()
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        cameraPromptDelivery.onPaused()
                        io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.activityPaused()
                    }
                    else -> Unit
                }
            },
        )
        io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.setListener {
            main.post { if (!destroyed) cameraPromptDelivery.onSignal() }
        }
        applyRendererScreenPolicy()
        configureEntityFilter(config)
        // Freeze the WebView when the panel screen is off (CPU/heat/memory), and reload the moment
        // connectivity returns if the frontend isn't connected — registered for the activity's lifetime.
        BuiltinDashboard.setScreenListener(screenListener)
        BuiltinDashboard.setHaLifecycleListener(haLifecycleListener)
        // Adopt the CURRENT screen state — an instance created while the panel is dark (a relaunch at
        // night) must not assume the screen is on: it would arm the handshake watchdog against a page
        // that's about to be frozen, and never freeze the WebView until the next real transition.
        screenAwake = BuiltinDashboard.screenAwakeNow
        val networkAvailable = registerNetworkCallback()
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
        lastTouchAt = SystemClock.elapsedRealtime()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (networkAvailable) buildAndLoad(config) else showWaitingForNetwork()
        // Created dark: let the initial load settle, then freeze (onLoadStarted skipped the watchdog;
        // onConnectionStatus freezes earlier if the frontend connects first).
        if (!screenAwake) main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /** Any touch (the WebView consumes them, but the activity sees them first) resets the idle clock. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            PanelTouchObserver.shared(this).noteActivityTouch()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        destroyed = true
        compatibilityAttempts.invalidate()
        homeDashboardAttempts.invalidate()
        activityScope.cancel()
        if (::activityConfig.isInitialized) activityConfig.unregisterChangeListener(rendererPowerListener)
        io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.setListener(null)
        wakeMediaRecovery.close()
        rendererGate.close()
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
        BuiltinDashboard.releaseActivityOwner(activityOwner)
        BuiltinDashboard.clearScreenListener(screenListener)
        BuiltinDashboard.clearHaLifecycleListener(haLifecycleListener)
        netCallback?.let { cb -> runCatching { conn?.unregisterNetworkCallback(cb) } }
        main.removeCallbacksAndMessages(null)
        teardownWeb()
        super.onDestroy()
    }

    /** Fully release the WebView so a long-lived process doesn't leak it: stop background loading,
     *  detach the JS bridge, destroy. `resumeTimers()` is process-global and pauseTimers() may have been
     *  called on a screen-off — without this, an activity destroyed while dark would leave JS timers
     *  frozen for every future WebView in this forever process (a never-blank violation). */
    private fun teardownWeb() {
        signInShownForUrl = null // the sign-in WebView is going away; a later call must rebuild it
        wakeMediaRecovery.invalidate()
        rendererGate.invalidate()
        externalBus.invalidate()
        externalBusSession = null
        v2BridgeDocument = null
        authQueue.clear()
        v2Handshake.reset()
        expectedPageStartUrl = null
        clearBusTimeouts()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(darkSettle)
        detachLifecycleBar()
        customView?.let { view -> runCatching { root?.removeView(view) } }
        runCatching { customViewCallback?.onCustomViewHidden() }
        customView = null
        customViewCallback = null
        web?.let { w ->
            runCatching { w.resumeTimers() }
            removeV2Listeners(w)
            runCatching { w.loadUrl("about:blank") }
            (w.parent as? ViewGroup)?.removeView(w)
            runCatching { w.destroy() }
        }
        web = null
        committedPageUrl = null
        swipe = null
        frontendConnected = false
    }

    private fun rendererCurrent(generation: Long, view: WebView? = null): Boolean =
        !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
            rendererGate.owns(generation) && (view == null || web === view)

    private fun bridgeCurrent(generation: Long, session: ExternalBusController.Session): Boolean =
        rendererCurrent(generation) && externalBus.owns(session)

    /** Rotate the bounded per-document controller before navigation. The listeners themselves stay
     * attached across ordinary HA reloads/redirects, as in upstream Android; their callback resolves
     * this current context rather than retaining a replaced document's credentials or session. */
    private fun beginBusDocument(
        view: WebView,
        config: Config,
        generation: Long,
    ): ExternalBusController.Session {
        clearBusTimeouts()
        authQueue.clear()
        val session = externalBus.beginDocument(generation, config.dashboardNativeKiosk)
        externalBusSession = session
        v2Handshake.begin(session)
        v2BridgeDocument = V2BridgeDocument(config, generation, session, entityFilterLease)
        try {
            if (v2ListenerView !== view) installV2Listeners(view, config)
        } catch (error: Throwable) {
            externalBus.invalidate()
            externalBusSession = null
            v2BridgeDocument = null
            throw ExternalV2BridgeUnavailable(error)
        }
        return session
    }

    private fun expectPageStart(url: String) {
        expectedPageStartUrl = url
    }

    private fun rotateBusDocument(view: WebView, config: Config, generation: Long): Boolean = try {
        beginBusDocument(view, config, generation)
        true
    } catch (error: ExternalV2BridgeUnavailable) {
        Log.e(TAG, "secure V2 listener attachment failed", error)
        showBlockedAdmissionScreen(
            getString(R.string.home_assistant_stopped_loading),
            getString(R.string.home_assistant_stopped_loading_detail),
            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
        )
        false
    }

    /** Local recovery documents never need credentials or the external bus. */
    private fun suspendBusDocument(view: WebView) {
        clearBusTimeouts()
        authQueue.clear()
        externalBus.invalidate()
        externalBusSession = null
        v2BridgeDocument = null
        removeV2Listeners(view)
    }

    private fun removeV2Listeners(view: WebView) {
        // Explicitly remove the legacy object as well: a V2-only renderer never leaves a V1 interface
        // installed, including across WebView reuse or a provider restoring internal state.
        runCatching { view.removeJavascriptInterface("externalApp") }
        if (!webViewFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
        runCatching { WebViewCompat.removeWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME) }
        if (v2ListenerView === view) v2ListenerView = null
    }

    private fun installV2Listeners(
        view: WebView,
        config: Config,
    ) {
        check(webViewFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val allowedOrigins = dashboardDocumentStartOrigins(config.haUrl)
        view.removeJavascriptInterface("externalApp")
        // Installation happens only before an HA navigation, never during an ordinary reload. Remove
        // defensively so a provider-restored name cannot make add fail or retain an unknown callback.
        runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
        runCatching { WebViewCompat.removeWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME) }
        WebViewCompat.addWebMessageListener(view, EXTERNAL_APP_V2, allowedOrigins) {
                callbackView, message, sourceOrigin, isMainFrame, _ ->
            val document = v2BridgeDocument ?: return@addWebMessageListener
            if (!v2CallbackCurrent(callbackView, document, sourceOrigin.toString(), isMainFrame)) {
                return@addWebMessageListener
            }
            when (val incoming = ExternalAppV2Protocol.parse(message.data)) {
                is ExternalAppV2Protocol.Incoming.GetExternalAuth -> {
                    if (enqueueV2Auth(document, incoming.payload)) markV2Observed(document.session)
                }
                is ExternalAppV2Protocol.Incoming.RevokeExternalAuth -> {
                    val reply = ExternalAuthProtocol.revokeReply(incoming.payload)
                    if (reply != null) markV2Observed(document.session)
                    evaluateBridgeReply(reply, document.generation, document.session)
                }
                is ExternalAppV2Protocol.Incoming.ExternalBus -> {
                    val bus = ExternalBusProtocol.parse(incoming.payload)
                    if (bus !is ExternalBusProtocol.Incoming.Malformed) markV2Observed(document.session)
                    handleExternalBus(bus, document.generation, document.session)
                }
                is ExternalAppV2Protocol.Incoming.Malformed ->
                    Log.w(TAG, "ignored malformed externalAppV2 envelope (${incoming.reason})")
                is ExternalAppV2Protocol.Incoming.Unknown -> Unit
            }
        }
        try {
            WebViewCompat.addWebMessageListener(view, HaPaneldV2Protocol.OBJECT_NAME, allowedOrigins) {
                    callbackView, message, sourceOrigin, isMainFrame, _ ->
                val document = v2BridgeDocument ?: return@addWebMessageListener
                if (!v2CallbackCurrent(callbackView, document, sourceOrigin.toString(), isMainFrame)) {
                    return@addWebMessageListener
                }
                handleHaPaneldV2(
                    HaPaneldV2Protocol.parse(message.data),
                    document.config,
                    document.generation,
                    document.session,
                    document.filterLease,
                )
            }
            v2ListenerView = view
        } catch (error: Throwable) {
            runCatching { WebViewCompat.removeWebMessageListener(view, EXTERNAL_APP_V2) }
            throw error
        }
    }

    private fun markV2Observed(session: ExternalBusController.Session) {
        if (!externalBus.owns(session)) return
        v2Handshake.observe(session)
    }

    private fun v2CallbackCurrent(
        callbackView: WebView,
        document: V2BridgeDocument,
        sourceOrigin: String?,
        isMainFrame: Boolean,
    ): Boolean = v2BridgeDocument === document && isMainFrame &&
        bridgeCurrent(document.generation, document.session) && web === callbackView &&
        sameDashboardOrigin(sourceOrigin, callbackView.url)

    private fun enqueueV2Auth(
        document: V2BridgeDocument,
        payload: String,
    ): Boolean {
        if (v2BridgeDocument !== document || !bridgeCurrent(document.generation, document.session)) return false
        val force = ExternalAuthProtocol.validAuthRequestForce(payload) ?: return false
        val request = PendingV2Auth(document.config, document.generation, document.session, payload, force)
        authQueue.offer(request)?.let(::resolveV2Auth)
        return true
    }

    private fun resolveV2Auth(work: BoundedAuthQueue.Work<PendingV2Auth>) {
        val request = work.request
        activityScope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        DashboardAuth.forConfig(
                            request.config,
                            force = request.force,
                            stillCurrent = { bridgeCurrent(request.generation, request.session) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "external auth resolution failed transiently: ${error.javaClass.simpleName}")
                    DashboardAuth.Result(null)
                }
                if (result.rejected && bridgeCurrent(request.generation, request.session)) {
                    onAuthRejected(request.generation)
                }
                evaluateBridgeReply(
                    ExternalAuthProtocol.authReply(
                        request.payload,
                        result.session?.accessToken,
                        result.session?.expiresInSec ?: 0L,
                    ),
                    request.generation,
                    request.session,
                )
            } finally {
                authQueue.complete(work) { next ->
                    bridgeCurrent(next.generation, next.session)
                }?.let(::resolveV2Auth)
            }
        }
    }

    private fun evaluateBridgeReply(
        script: String?,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (script != null && bridgeCurrent(generation, session)) web?.evaluateJavascript(script, null)
    }

    private fun handleHaPaneldV2(
        incoming: HaPaneldV2Protocol.Incoming,
        config: Config,
        generation: Long,
        session: ExternalBusController.Session,
        filterLease: EntityFilterTelemetry.Lease?,
    ) {
        if (!bridgeCurrent(generation, session)) return
        when (incoming) {
            HaPaneldV2Protocol.Incoming.EntityFilterSubscriptionModified -> {
                val lease = filterLease ?: return
                if (config.dashboardEntityFilterEnabled) EntityFilterTelemetry.subscriptionModified(lease)
            }
            is HaPaneldV2Protocol.Incoming.EntityFilterTrafficMetrics -> {
                val lease = filterLease ?: return
                if (incoming.payload.length > 512) return
                runCatching { EntityFilterProtocol.parseTrafficBatch(incoming.payload) }
                    .onSuccess { EntityFilterTelemetry.traffic(lease, it) }
            }
            is HaPaneldV2Protocol.Incoming.EntityLearningAccesses -> {
                if (!config.dashboardEntityLearningEnabled) return
                if (incoming.payload.length < EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS) {
                    EntityLearningRuntime.recordAccessBatch(incoming.payload)
                } else FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER)
            }
            is HaPaneldV2Protocol.Incoming.EntityLearningMetrics -> {
                if (!config.dashboardEntityLearningEnabled) return
                if (incoming.payload.length < EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS) {
                    EntityLearningRuntime.recordMetricBatch(incoming.payload)
                } else FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER)
            }
            is HaPaneldV2Protocol.Incoming.Malformed ->
                Log.w(TAG, "ignored malformed haPaneldV2 envelope (${incoming.reason})")
            is HaPaneldV2Protocol.Incoming.Unknown -> Unit
        }
    }

    private fun clearBusTimeouts() {
        busTimeouts.values.forEach(main::removeCallbacks)
        busTimeouts.clear()
    }

    private fun entityFilterSignature(config: Config): String {
        val learning = ":learning=${config.dashboardEntityLearningEnabled}"
        if (!config.dashboardEntityFilterEnabled) return "disabled$learning"
        return runCatching {
            val ids = EntityFilterProtocol.normalize(config.dashboardEntityFilterIds)
            "enabled:${EntityFilterProtocol.hash(ids)}:${config.haUrl}$learning"
        }.getOrDefault("invalid$learning")
    }

    private fun holdForEntityBootstrap(config: Config): Boolean =
        shouldHoldRendererForEntityBootstrap(
            learningEnabled = config.dashboardEntityLearningEnabled,
            filterEnabled = config.dashboardEntityFilterEnabled,
            autoScopeUnverified = AutoScopeVerification.unverified(config.homeDashboard),
        )

    /** Prepare the exact allow-list for document-start interception. Automatic filtering fails closed:
     *  an unavailable interceptor holds the native diagnostic screen rather than opening HA unfiltered. */
    private fun configureEntityFilter(config: Config) {
        entityFilterNativeHold = null
        entityFilterSignature = entityFilterSignature(config)
        if (!entityFilterSignature.startsWith("enabled:")) {
            val lease = EntityFilterTelemetry.stopped()
            entityFilterLease = lease
            if (invalidEntityFilterFailureDisposition(
                    signature = entityFilterSignature,
                    automaticLearningEnabled = config.dashboardEntityLearningEnabled,
                    filterConfigured = config.dashboardEntityFilterEnabled,
                ) == EntityFilterFailureDisposition.HOLD_NATIVE
            ) {
                EntityFilterTelemetry.held(lease, "invalid_configuration")
                entityFilterNativeHold = EntityFilterNativeHold(
                    error = "invalid_configuration",
                    detail = "The entity list saved on this panel is not valid, so it cannot be used.",
                )
            }
            return
        }
        val ids = runCatching { EntityFilterProtocol.normalize(config.dashboardEntityFilterIds) }
            .getOrElse {
                Log.e(TAG, "invalid entity-filter configuration", it)
                val lease = EntityFilterTelemetry.stopped()
                entityFilterLease = lease
                if (entityFilterFailureDisposition(
                        automaticLearningEnabled = config.dashboardEntityLearningEnabled,
                        filterConfigured = config.dashboardEntityFilterEnabled,
                    ) == EntityFilterFailureDisposition.HOLD_NATIVE
                ) {
                    EntityFilterTelemetry.held(lease, "invalid_configuration")
                    entityFilterNativeHold = EntityFilterNativeHold(
                        error = "invalid_configuration",
                        detail = "The entity list saved on this panel is not valid, so it cannot be used.",
                    )
                } else {
                    entityFilterSignature = "disabled"
                    EntityFilterTelemetry.failed(lease, "invalid_configuration")
                    EntityFilterTelemetry.directFallback(lease)
                }
                return
            }
        val lease = EntityFilterTelemetry.started(ids)
        entityFilterLease = lease
        if (!webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "entity filter unavailable: document-start script unsupported")
            if (entityFilterFailureDisposition(
                    automaticLearningEnabled = config.dashboardEntityLearningEnabled,
                    filterConfigured = true,
                ) == EntityFilterFailureDisposition.HOLD_NATIVE
            ) {
                EntityFilterTelemetry.held(lease, "document_start_unsupported")
                entityFilterNativeHold = EntityFilterNativeHold(
                    error = "document_start_unsupported",
                    detail = "This panel's web viewer, Android System WebView, is too old to filter what Home Assistant sends. Updating it should fix this.",
                )
            } else {
                EntityFilterTelemetry.failed(lease, "document_start_unsupported")
                EntityFilterTelemetry.directFallback(lease)
            }
            return
        }
    }

    /** Record an interceptor installation failure. Returns true when the caller must abort WebView
     *  creation and re-enter the native diagnostic screen instead of loading HA unfiltered. */
    private fun fallbackFromEntityFilterInterceptor(error: Throwable, automaticLearningEnabled: Boolean): Boolean {
        Log.e(TAG, "failed to install entity-filter subscription interceptor", error)
        val lease = entityFilterLease ?: return false
        val hold = entityFilterFailureDisposition(
            automaticLearningEnabled = automaticLearningEnabled,
            filterConfigured = entityFilterSignature.startsWith("enabled:"),
        ) == EntityFilterFailureDisposition.HOLD_NATIVE
        if (hold) {
            EntityFilterTelemetry.held(lease, "document_start_install")
            entityFilterNativeHold = EntityFilterNativeHold(
                error = "document_start_install",
                detail = "The panel could not set up the entity filtering this time.",
            )
        } else {
            EntityFilterTelemetry.failed(lease, "document_start_install")
            entityFilterSignature = "disabled"
            EntityFilterTelemetry.directFallback(lease)
        }
        return hold
    }

    private fun retryEntityFilter(config: Config) {
        main.removeCallbacks(entityFilterRetry)
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
        configureEntityFilter(config)
        buildAndLoad(config)
    }

    private fun scheduleEntityFilterRetry() {
        // Carry the deadline rather than the delay. This is re-entered by the status rerender, which a
        // theme write or a rotation triggers, and re-posting at the FULL delay each time means a rung
        // measured in minutes never elapses on a panel whose theme is being written. The same defect was
        // fixed for the admission timer; it was not fixed here.
        main.removeCallbacks(entityFilterRetry)
        val now = SystemClock.elapsedRealtime()
        val remaining = entityFilterRetryDueAtMs - now
        val delay = if (entityFilterRetryDueAtMs > 0L && remaining > 0L) {
            remaining
        } else {
            val fresh = entityFilterRetryPolicy.nextDelay(screenAwake) ?: return
            entityFilterRetryDueAtMs = now + fresh
            fresh
        }
        main.postDelayed(entityFilterRetry, delay)
    }

    /** Single scheduling authority for the post-reload settle window: cancel any pending settle and
     *  re-arm it [DARK_SETTLE_MS] out. See [darkSettle] for why a screen-off reload runs live for a
     *  window before freezing. */
    private fun scheduleDarkSettle() {
        main.removeCallbacks(darkSettle)
        main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /**
     * Screen on/off fan-out. Screen ON → resume rendering + JS timers, and re-arm the handshake watchdog
     * if the frontend isn't connected (recover a page that broke while frozen). Screen OFF → pause
     * rendering and disarm the watchdog (no handshake retries while timers are frozen); then either shed
     * memory with an invisible reload (kept live through a settle window, see [darkSettle]) if one is due,
     * or freeze JS timers. `pauseTimers()` is process-global, which is fine — the dashboard is our only
     * live WebView.
     */
    private fun onScreenChanged(awake: Boolean) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        val wasAwake = screenAwake
        screenAwake = awake
        if (awake && entityFilterNativeHold != null) scheduleEntityFilterRetry()
        if (!awake) main.removeCallbacks(entityFilterRetry)
        val w = web ?: return
        if (awake) {
            main.removeCallbacks(darkSettle)
            w.resumeTimers(); w.onResume()
            lastTouchAt = SystemClock.elapsedRealtime() // a wake implies presence — don't instantly snap home
            if (!frontendConnected && !authLatched) armWatchdog(INITIAL_HANDSHAKE_MS)
            val frontendHealthy = frontendConnected && !authLatched && !interstitialShown
            if (!wasAwake && awake) {
                if (frontendHealthy) armWakeMediaRecovery(w, rendererGeneration)
                else if (!authLatched && !interstitialShown) wakeMediaRecovery.defer(rendererGeneration)
            }
        } else {
            wakeMediaRecovery.invalidate()
            w.onPause()
            main.removeCallbacks(watchdog) // a paused WebView can't complete the handshake — don't loop
            if (authLatched) { w.pauseTimers(); return }
            if (reloadDue()) {
                // Reload to shed memory, but DON'T pause timers yet — a paused WebView can't load. Let it
                // run; onConnectionStatus freezes it the moment it connects, or darkSettle freezes it after
                // the settle window if it never does. Either way it doesn't churn all night.
                reloadPending = false
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("quiet reload (screen off, due)")
                scheduleDarkSettle()
            } else {
                w.pauseTimers()
            }
        }
    }

    /** WebView resume does not guarantee that HA's existing WebRTC cards rebuild a transport. After a
     * real wake, sample only visible playing/autoplay media and allow one full-page recovery if none of
     * it advances. Every callback is owned by both the renderer generation and the wake cycle. */
    private fun armWakeMediaRecovery(
        w: WebView,
        generation: Long,
        ticket: WakeMediaRecoveryTicket = wakeMediaRecovery.begin(generation),
    ) {
        main.postDelayed({
            if (!wakeMediaCurrent(ticket, w)) return@postDelayed
            w.evaluateJavascript(WakeMediaRecoveryScript.arm(ticket.cycle)) { result ->
                if (!wakeMediaCurrent(ticket, w)) return@evaluateJavascript
                when (wakeMediaRecovery.onArmResult(ticket, javascriptIntResult(result) ?: -1)) {
                    WakeMediaRecoveryAction.INSPECT ->
                        main.postDelayed({ inspectWakeMedia(ticket, w) }, WAKE_MEDIA_SAMPLE_MS)
                    WakeMediaRecoveryAction.NONE, WakeMediaRecoveryAction.RELOAD -> Unit
                }
            }
        }, WAKE_MEDIA_SETTLE_MS)
    }

    private fun inspectWakeMedia(ticket: WakeMediaRecoveryTicket, w: WebView) {
        if (!wakeMediaCurrent(ticket, w)) return
        w.evaluateJavascript(WakeMediaRecoveryScript.inspect(ticket.cycle)) { result ->
            if (!wakeMediaCurrent(ticket, w)) return@evaluateJavascript
            if (wakeMediaRecovery.onInspectResult(ticket, javascriptIntResult(result) == 1) == WakeMediaRecoveryAction.RELOAD) {
                Log.w(TAG, "visible dashboard media did not resume after screen wake — reloading once")
                BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime())
                doReload("visible media stalled after screen wake")
            }
        }
    }

    private fun wakeMediaCurrent(ticket: WakeMediaRecoveryTicket, w: WebView): Boolean =
        screenAwake && wakeMediaRecovery.owns(ticket) && rendererCurrent(ticket.rendererGeneration, w)

    /** A reload is worthwhile now if memory pressure asked for one, or it's been a long time since the
     *  last full load (WebView memory accretes over days). */
    private fun reloadDue(): Boolean =
        reloadPending || (SystemClock.elapsedRealtime() - lastFullLoadAt > RELOAD_INTERVAL_MS)

    /** Portable memory ceiling (see [periodicCheck]): reload invisibly at a screen-off past the interval,
     *  or force a brief visible reload past the hard cap so a panel that never sleeps still sheds memory. */
    private fun onPeriodicCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (authLatched) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        // The memory-ceiling reload measures idle from lastFullLoadAt, which the on-panel sign-in never
        // sets because it performs no dashboard load. On a panel that has been up for longer than the
        // interval that reads as "idle for the whole uptime", so the first check would reload — throwing
        // away a sign-in the user is part-way through, and reintroducing exactly the reload churn this
        // screen exists to end. Nothing here needs shedding: the sign-in page is a single small form.
        if (signInShownForUrl != null) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        val idle = SystemClock.elapsedRealtime() - lastFullLoadAt
        when {
            idle < RELOAD_INTERVAL_MS -> {}
            !screenAwake -> {
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("periodic (screen off, ${idle / 3_600_000}h idle)")
                scheduleDarkSettle()
            }
            idle >= RELOAD_HARD_MS -> doReload("periodic hard cap (${idle / 3_600_000}h idle)")
            else -> reloadPending = true // visible now — wait for the next screen-off to reload invisibly
        }
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (maintenanceFence.stop(this)) return
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // The sign-in form has no accreted memory worth shedding, and reloading it would discard a
        // part-entered Home Assistant login. Leave it alone; it is short-lived by nature.
        if (signInShownForUrl != null) return
        // UI_HIDDEN is a routine lifecycle signal, NOT memory pressure — it's delivered on every
        // screen-off (activities stop when the display sleeps), so reacting to it made every screen-off
        // full-reload the frontend, defeating the 6-hour quiet-reload ceiling in onScreenChanged.
        if (level == TRIM_MEMORY_UI_HIDDEN) return
        // Any real trim signal means shed the WebView's accreted memory. Gate at RUNNING_LOW so we catch
        // BOTH the pre-14 foreground RUNNING_* levels AND the pressure levels Android 14 actually
        // delivers (BACKGROUND / MODERATE / COMPLETE, all ≥ RUNNING_LOW's value) — the previous
        // RUNNING_MODERATE..CRITICAL window was dead code on the fleet's Android-14 panels.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            reloadPending = true
            // If we're not the visible dashboard, reload right now rather than risk an OOM renderer kill.
            if (!screenAwake || !BuiltinDashboard.foreground) {
                reloadPending = false
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("memory pressure L$level")
                if (!screenAwake) scheduleDarkSettle()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (maintenanceFence.stop(this)) return
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !authLatched &&
            (!screenAwake || !BuiltinDashboard.foreground)) {
            lastFullLoadAt = SystemClock.elapsedRealtime()
            doReloadNoWatchdog("onLowMemory")
            if (!screenAwake) scheduleDarkSettle()
        }
    }

    /** Hand off to ha-paneld's admin launcher and finish — the never-strand fallback for a missing
     *  WebView or a crash-looping renderer. */
    private fun fallbackToLauncher() {
        cancelAdmissionAutoRetry()
        runCatching {
            startActivity(Intent(this, AdminLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "admin-launcher fallback failed: ${it.message}") }
        finish()
    }

    /** Hand off to MainActivity's wordmark + QR/configure page. This is the required first visible
     *  surface for a fresh install or cleared configuration; the admin launcher is only for explicit
     *  panel-admin entry and crash/recovery administration. */
    private fun fallbackToFirstRunSurface() {
        cancelAdmissionAutoRetry()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "first-run surface fallback failed: ${it.message}") }
        finish()
    }

    /** Allow a renderer rebuild if within budget. The budget lives in [BuiltinDashboard] (process-
     *  global), NOT here: fallbackToLauncher() finishes this instance and the kiosk/watchdog return
     *  loop starts a fresh one — a per-instance counter would reset every relaunch, turning a reliably
     *  crashing page into an infinite fallback/relaunch churn. Exhaustion engages the renderer latch
     *  ([BuiltinDashboard.rendererLatched]), which suppresses automatic relaunches for a cooldown. */
    private fun allowRebuild(): Boolean =
        BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())

    /** A singleTask relaunch reaching the live renderer means one of three things, disambiguated here:
     *  a **navigate** ([BuiltinDashboard.navPath] set — one-shot, consumed), an explicit **reload**
     *  ([BuiltinDashboard.consumeReloadRequest] — set by reloadDashboard / a credential change), or a
     *  plain **bring-to-foreground** (the kiosk/watchdog return loops). A healthy foregrounded page is
     *  left untouched — snapping back from Recents must not blank the dashboard with a full reload. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (maintenanceFence.stop(this)) return
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        val config = Config(this)
        if (shouldRouteDashboardHomeToAdmin(config.launcherPackage, packageName, intent?.action, intent?.categories)) {
            Log.i(TAG, "HOME invoked with Panel admin selected — opening the admin launcher")
            fallbackToLauncher()
            return
        }
        if (!config.builtInRendererReady()) {
            // Same escape as onCreate. This path matters just as much: saving a Home Assistant URL from
            // the browser relaunches this singleTask activity, so a running panel arrives HERE rather
            // than in onCreate, and bouncing would undo the save the user just made.
            if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
                Log.i(TAG, "Home Assistant URL set without credentials — continuing to the on-panel sign-in")
            } else {
                // Same never-strand guard as onCreate: the URL was cleared while we were running — a load
                // would strand the built-in renderer without a usable authenticated route. Return to the
                // QR/configure surface so the user can repair the panel from a browser.
                Log.w(TAG, "built-in renderer no longer ready — opening the first-run configure surface instead")
                fallbackToFirstRunSurface()
                return
            }
        }
        val normalizedUrl = config.haUrl.trim().trimEnd('/')
        if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
            if (signInShownForUrl == normalizedUrl && web != null) {
                Log.i(TAG, "on-panel sign-in still pending — keeping the live sign-in WebView")
            } else {
                buildAndLoad(config)
            }
            return
        }
        // Credentials arrived while the on-panel sign-in was showing — typically because the user signed
        // in from a browser instead, but also provisioning or a token restore. The current WebView is the
        // bare sign-in page: its client only permits the OAuth navigation and it carries no dashboard
        // bridge, so the reuse path below would load the dashboard URL into it, HA would find no external
        // auth and bounce straight back to its own login, and the panel would appear to loop the sign-in
        // page forever. Tear it down and build the real renderer instead.
        if (signInShownForUrl != null && !haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
            Log.i(TAG, "credentials present — leaving the on-panel sign-in for the dashboard")
            BuiltinDashboard.consumeSupersededReload()
            activityConfig = config
            unlatchAuth("credentials arrived during sign-in")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        if (compatibilityReadyUrl != null && compatibilityReadyUrl != normalizedUrl) {
            Log.i(TAG, "Home Assistant endpoint changed — rebuilding and rechecking the V2 renderer")
            BuiltinDashboard.consumeSupersededReload()
            activityConfig = config
            compatibilityJob?.cancel()
            compatibilityCheckingOwner = null
            compatibilityReadyUrl = null
            compatibilityAttempts.invalidate()
            unlatchAuth("Home Assistant endpoint change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        val activeHomeDashboardOwner = homeDashboardResolution?.owner ?: homeDashboardCheckingOwner
        if (activeHomeDashboardOwner != null && activeHomeDashboardOwner != homeDashboardOwner(config)) {
            Log.i(TAG, "dashboard authority changed — resolving the authenticated dashboard list again")
            BuiltinDashboard.consumeSupersededReload()
            invalidateHomeDashboardResolution()
            unlatchAuth("dashboard authority change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        // The filter endpoint reloads this singleTask activity after committing. Document-start scripts
        // cannot be replaced in an existing WebView, so a filter-set change or learning-mode change
        // deliberately rebuilds only the WebView while keeping the foreground service and app process alive.
        val nextFilterSignature = entityFilterSignature(config)
        if (nextFilterSignature != entityFilterSignature) {
            Log.i(TAG, "entity instrumentation changed — rebuilding dashboard WebView")
            // The learner's reload lands here with its reason pending. Only it is a learning restart;
            // a hand edit of the filter or a settings change announces as a lone restart.
            announceDeliberateRestart(
                getString(R.string.optimizing_entities_restart),
                learning = BuiltinDashboard.consumeSupersededReload() == BuiltinDashboard.LEARNING_RELOAD_REASON,
            )
            unlatchAuth("entity-filter change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        // Same reason as the filter set above: the theme policy is carried by a document-start script,
        // which the WebView fixes at creation. Rebuilding is also what makes the change reversible —
        // the fresh script runs the hand-back transaction before Home Assistant's own bootstrap reads
        // the store, so the restored value is the one the frontend boots with.
        val nextThemeSignature = config.dashboardTheme
        if (nextThemeSignature != dashboardThemeSignature) {
            Log.i(TAG, "dashboard theme policy changed — rebuilding dashboard WebView")
            BuiltinDashboard.consumeSupersededReload()
            announceDeliberateRestart(getString(R.string.applying_dashboard_theme))
            unlatchAuth("theme policy change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        val nav = BuiltinDashboard.consumeNavPath()
        val reload = BuiltinDashboard.consumeReloadRequest()
        val reloadReason = BuiltinDashboard.consumeReloadReason()
        if (reload) announceDeliberateRestart(reloadReason)
        val w = web
        val healthy = w != null && frontendConnected && !authLatched && !interstitialShown
        if (healthy && !reload) externalBusSession?.let { session ->
            dispatchBus(externalBus.updateKioskPreference(session, config.dashboardNativeKiosk))
        }
        if (nav == null && !reload && healthy) return   // foreground-only: page is fine, nothing to do
        if (nav != null && !reload && healthy) {
            // Navigate on a healthy page: an instant bus re-navigate (same as idle-return), not a full
            // page load — the JS bundle + websocket stay live.
            Log.i(TAG, "navigate -> /$nav (bus)")
            sendBusNavigate(nav)
            return
        }
        unlatchAuth("new load") // a deliberate reload/navigate (or a config change) is the retry consent
        retryPolicy.reset()
        interstitialShown = false
        if (w == null) { buildAndLoad(config); return }  // defensive: relaunched with no WebView built
        // Re-apply force-dark (only set at WebView creation otherwise) and, on the no-system-dark-mode
        // panels, write the panel's dark_mode into HA's own per-device theme store before the fresh
        // load — that store is what actually re-renders HA (see selectedThemeJs; force-dark alone is a
        // no-op on HA). The write must precede loadUrl: localStorage is synchronous and origin-scoped,
        // so the reloaded frontend boots straight into the new scheme.
        applyForceDark(w)
        val targetPath = nav ?: resolvedHomeDashboard(config)
            ?: return readmitForHomeDashboard(if (reload) "reload" else "relaunch")
        val url = ExternalAuthProtocol.dashboardUrl(config.haUrl, targetPath)
        noteDeliberateDashboardNavigation()
        noteAppNavigationTarget(targetPath)
        val generation = rendererGeneration
        // Same suppression as the seed in createWebView: while Dark/Light owns the `dark` field, a
        // dark_mode toggle must not write it as well. The policy's own document-start script re-applies
        // on the load this reload is about to start, so nothing is lost by staying out of the way.
        if (android.os.Build.VERSION.SDK_INT < 29 && !DashboardTheme.forces(config.dashboardTheme)) {
            // The theme write must COMPLETE before the navigation — evaluateJavascript is async (queued
            // to the JS thread), and a loadUrl issued right after can tear the page down first, losing
            // the write. The result callback runs after evaluation, on the UI thread.
            w.evaluateJavascript(ExternalAuthProtocol.selectedThemeJs(Config(this).darkMode, onlyIfAbsent = false)) {
                if (rendererCurrent(generation, w)) {
                    if (!rotateBusDocument(w, config, generation)) return@evaluateJavascript
                    expectPageStart(url)
                    w.loadUrl(url)
                }
            }
        } else {
            if (!rotateBusDocument(w, config, generation)) return
            expectPageStart(url)
            w.loadUrl(url)
        }
        onLoadStarted()
    }

    // --- frontend-handshake watchdog + reconnect ---

    /** Called after every page load that should be health-checked (initial, navigate, watchdog reload,
     *  network-regain reload): reset the connected state + arm the handshake watchdog — but only while the
     *  screen is awake, since a paused WebView can't run the JS that completes the handshake. */
    private fun onLoadStarted() {
        wakeMediaRecovery.invalidate()
        frontendConnected = false
        clearedThisLoad = false
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordLoadStart(lastFullLoadAt) // TTI origin (first call since process start = cold)
        if (screenAwake) armWatchdog(INITIAL_HANDSHAKE_MS) else main.removeCallbacks(watchdog)
    }

    private fun armWatchdog(ms: Long) {
        if (destroyed) return
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, ms)
    }

    private fun onWatchdogTimeout() {
        // Never retry on a dead activity or latched — both runaway loops. While frozen (screen off) the
        // policy answers NONE: a paused page cannot complete the handshake, so that fire is not a miss.
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || frontendConnected || authLatched) return
        val step = retryPolicy.onWatchdogFired(screenAwake)
        if (step == HandshakeRecoveryStep.NONE) return
        val session = externalBusSession
        if (session != null && v2Handshake.onTimeout(session)) {
            showBlockedAdmissionScreen(
                getString(R.string.home_assistant_unresponsive),
                getString(R.string.home_assistant_unresponsive_detail),
                AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
            )
            return
        }
        if (step != HandshakeRecoveryStep.RELOAD) {
            escalateHandshakeRecovery(step)
            return
        }
        Log.w(TAG, "frontend handshake watchdog fired (no connection-status:connected) — reloading")
        // The reconnect grace expired: this load now owns recovery, so a later connection callback must
        // not revive the pre-timeout wake ticket against the replacement target.
        wakeMediaRecovery.invalidate()
        if (!reloadTarget()) return
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordRendererReload(lastFullLoadAt) // involuntary: handshake stalled
        BuiltinDashboard.recordLoadStart(lastFullLoadAt)      // warm TTI origin for the recovery load
        clearedThisLoad = false
        armWatchdog(retryPolicy.afterRetry())
    }

    /**
     * A reload has already failed [DashboardRetryPolicy.consecutiveMisses] times in a row, so do more
     * than reload. [HandshakeRecoveryStep.FRESH_LOAD] loads the dashboard target afresh into the held
     * WebView (a new bus and auth document, never `reload()`) and re-runs the authenticated dashboard
     * resolution behind it, which corrects the page if the answer changed. [HandshakeRecoveryStep.RECREATE_WEBVIEW]
     * replaces the WebView as well, through the same teardown and build a settings change uses. Neither
     * touches the crash budget or relaunches anything: the renderer crash path stays the only owner of
     * both.
     */
    private fun escalateHandshakeRecovery(step: HandshakeRecoveryStep) {
        val misses = retryPolicy.consecutiveMisses
        if (step == HandshakeRecoveryStep.FRESH_LOAD) {
            Log.w(
                TAG,
                "frontend handshake missed $misses times in a row — escalating: fresh dashboard resolution " +
                    "and load, then WebView recreation, with backoff",
            )
        }
        val config = Config(this)
        // The resolution the page is showing, if it still belongs to the current credential and path. It
        // goes back to provisional, so the resolution below refreshes it live instead of reusing it.
        val resolved = homeDashboardResolution
            ?.takeIf { it.owner == homeDashboardOwner(config) && it.resolution.path != null }
        val recreate = step == HandshakeRecoveryStep.RECREATE_WEBVIEW || resolved == null || web == null
        Log.w(
            TAG,
            "frontend handshake watchdog fired (miss $misses) — " +
                if (recreate) "recreating the dashboard WebView" else "fresh dashboard load",
        )
        // Same reason as the reload: this load now owns recovery.
        wakeMediaRecovery.invalidate()
        resolved?.let { homeDashboardResolution = it.copy(confirmed = false) }
        if (recreate) {
            // As at every other rebuild site: a surviving flag would make the new WebView's first load
            // error skip the reconnecting page and leave Chromium's own error page on the panel.
            interstitialShown = false
            teardownWeb()
            buildAndLoad(config)
        } else {
            loadCorrectedHomeDashboard()
            // A failed bus rotation already replaced the page with a blocked screen; resolving now would
            // build a renderer over it.
            if (web != null) {
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
                resolveHomeDashboardAndLoad(config)
            }
        }
        // No renderer means a native screen took over and owns its own recovery.
        if (web == null) return
        BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary: handshake stalled
        // The new load armed the cold-start window; keep it no shorter than the reload it replaced.
        armWatchdog(maxOf(INITIAL_HANDSHAKE_MS, retryPolicy.afterRetry()))
    }

    /** Fired from the external-bus `connection-status` message: the frontend telling us it connected or
     *  dropped its websocket. Posted onto the main thread, so it can race onDestroy — guard on [destroyed]. */
    private fun onConnectionStatus(
        event: ExternalBusProtocol.ConnectionEvent,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!bridgeCurrent(generation, session)) return
        if (event == ExternalBusProtocol.ConnectionEvent.CONNECTED) {
            frontendConnected = true
            BuiltinDashboard.recordConnected(SystemClock.elapsedRealtime()) // TTI: load-start → interactive
            // Launch is over: the lifecycle socket may now be demanded without competing with startup.
            BuiltinDashboard.onRendererSettled(activityOwner)
            // First-ever proven render: from here on, an unfinished setup journey is a REPAIR of a panel
            // that once worked, and the wizard words it that way instead of reading like a factory reset.
            if (::activityConfig.isInitialized && !activityConfig.setupEverCompleted) {
                activityConfig.setupEverCompleted = true
            }
            interstitialShown = false // real page demonstrably loaded
            unlatchAuth("frontend connected") // auth demonstrably works — clear any stale latch + counters
            main.removeCallbacks(watchdog)
            retryPolicy.escalatedTo?.let { reached ->
                Log.i(
                    TAG,
                    "frontend handshake recovered after ${retryPolicy.consecutiveMisses} consecutive misses " +
                        "(escalated to ${reached.name.lowercase()})",
                )
            }
            retryPolicy.reset()
            wakeMediaRecovery.activateDeferred(generation)?.let { ticket ->
                web?.let { w -> armWakeMediaRecovery(w, generation, ticket) }
            }
            if (!clearedThisLoad) {
                // Drop the auth/redirect history entries so Back can't reach a stale login page, and
                // persist cookies so an unclean process death right after login doesn't lose the session.
                web?.clearHistory(); clearedThisLoad = true
                runCatching { CookieManager.getInstance().flush() }
            }
            captureDashboardTheme(generation, session)
            // If we connected while the screen is off (a screen-off memory reload), freeze now — the fresh
            // page is loaded + connected, so there's nothing left to do behind the dark screen.
            if (!screenAwake) { main.removeCallbacks(darkSettle); web?.pauseTimers() }
            Log.i(TAG, "frontend connected")
            dispatchBus(externalBus.onConnection(session, true))
        } else {
            val wasConnected = frontendConnected
            frontendConnected = false
            // auth-invalid is the frontend saying HA refused its token. One can be a race around a token
            // refresh; several in a row mean the credential is dead — latch instead of reload-looping.
            if (event == ExternalBusProtocol.ConnectionEvent.AUTH_INVALID && ++authInvalids >= AUTH_INVALID_LATCH) {
                latchAuthFailure("repeated auth-invalid")
                return
            }
            externalBus.onConnection(session, false)
            // disconnected: give the frontend's own reconnect a grace window, then reload — but only
            // while awake (a frozen WebView can't reconnect; wake re-arms the handshake watchdog).
            if (screenAwake && !authLatched) {
                val delay = retryPolicy.connectionFailureDelay(wasConnected)
                Log.i(TAG, "frontend '${event.wireValue}' — arming retry watchdog in ${delay}ms (wasConnected=$wasConnected)")
                armWatchdog(delay)
            }
        }
    }

    /** Remember HA's own per-device theme so the native pre-WebView launch screen matches it on the
     * next boot, and publish the scheme the frontend is ACTUALLY rendering so `/api/v1/status` can say
     * when an explicit Home Assistant theme is overriding this panel's Dashboard theme. The JS returns
     * only booleans/null in a fixed two-letter shape, avoiding any localStorage contents in logs. */
    private fun captureDashboardTheme(
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!bridgeCurrent(generation, session)) return
        // Theme-update events can arrive faster than WebView callbacks. Only the newest read from the
        // current connection may publish, so an older answer cannot overwrite a later HA choice.
        val observationEpoch = ++themeObservationEpoch
        web?.evaluateJavascript(ExternalAuthProtocol.THEME_OBSERVATION_JS) { result ->
            if (!bridgeCurrent(generation, session) || !frontendConnected ||
                observationEpoch != themeObservationEpoch
            ) return@evaluateJavascript
            val observed = ExternalAuthProtocol.parseThemeObservation(result)
            when (observed.storedDark) {
                true -> Config(this).setDashboardThemeDark(true)
                false -> Config(this).setDashboardThemeDark(false)
                // An explicit absence is an observation too. Recording only true/false made the key
                // write-only, so a stored theme that later went away (or a forced one this panel just
                // handed back) kept outranking the system setting on every native screen.
                null -> if (observed.valid) Config(this).clearDashboardThemeDark()
            }
            RendererAdmissionRuntime.setEffectiveTheme(activityOwner, observed.effectiveDark)
        }
    }

    /** The token refresher got a terminal rejection from HA. One could be a freak intermediary event;
     *  two consecutive responses mean the unchanged login settings cannot recover — latch. */
    private fun onAuthRejected(generation: Long) {
        if (!rendererCurrent(generation) || authLatched) return
        if (++refreshRejects >= REFRESH_REJECT_LATCH) latchAuthFailure("login settings rejected by HA")
    }

    /** Stop the retry machinery and show a clear on-panel message naming the fix — a panel retrying a
     *  rejected login settings forever is churn with no exit, and a silent blank is a never-blank violation.
     *  The `:8888` health warnings surface the same state off-panel via [BuiltinDashboard.authLatched]. */
    private fun latchAuthFailure(why: String) {
        if (authLatched) return
        authLatched = true
        renderAuthLatchPage()
        Log.e(TAG, "auth latched ($why) — showing fix instructions, no further retries until reload/reconfig")
    }

    /**
     * Draw the terminal sign-in page, separately from latching.
     *
     * Latching is one-way and guards its own re-entry, so a theme change could not redraw this page
     * through it — the guard returned before any drawing happened and the page kept the palette and
     * artwork it was first built with.
     */
    private fun renderAuthLatchPage() {
        val stillLatched = authLatched
        if (!stillLatched) return
        BuiltinDashboard.setActivityAuthLatched(activityOwner, true)
        main.removeCallbacks(watchdog)
        val ip = io.github.maxlyth.hapaneld.metrics.PanelMetrics.shared.ipAddress()
        val cfg = ip?.let { "http://$it:8888/configure" } ?: getString(R.string.config_activity_label)
        web?.let(::suspendBusDocument)
        // Branded like the native screens, and for the sharpest reason on any of them: the heading
        // names Home Assistant, the page is drawn by the browser that normally shows Home Assistant,
        // and the decision being reported — stop retrying — is entirely ha-paneld's.
        val dark = StatusSurface.darkFor(this, Config(this))
        val palette = statusPalette(dark)
        val rejectedTitle = android.text.TextUtils.htmlEncode(getString(R.string.sign_in_rejected_title))
        val rejectedDetail = android.text.TextUtils.htmlEncode(getString(R.string.sign_in_rejected_terminal_detail))
        val rejectedFix = android.text.TextUtils.htmlEncode(getString(R.string.sign_in_rejected_fix, cfg))
        web?.loadDataWithBaseURL(
            null,
            """<!doctype html><html><body style="background:${palette.background};color:${palette.body};
               font-family:sans-serif;display:flex;align-items:flex-start;justify-content:center;
               min-height:100vh;margin:0;padding:6vh 0;box-sizing:border-box">
               <div style="max-width:80%;text-align:center">
               ${statusBrandHtmlHeader(this, dark, palette)}
               <h1 style="color:${palette.error}">$rejectedTitle</h1>
               <p style="font-size:1.3em">$rejectedDetail</p>
               <p style="font-size:1.3em">$rejectedFix</p>
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    private fun unlatchAuth(why: String) {
        authInvalids = 0; refreshRejects = 0
        if (!authLatched) return
        authLatched = false
        BuiltinDashboard.setActivityAuthLatched(activityOwner, false)
        Log.i(TAG, "auth latch cleared ($why)")
    }

    private fun onNetworkAvailable() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // Ignore only the registration callback when the activity started online. If it started without
        // a default network, its first onAvailable is the boot-time Wi-Fi arrival and must recover now.
        if (networkRecovery?.onAvailable() != true) return
        if (signInShownForUrl != null) {
            // The physical sign-in view has no V2 bus document or handshake. Let its ordinary browser
            // connection recover; routing it through dashboard reload/watchdog machinery can erase a
            // partially typed password and can never satisfy the dashboard handshake.
            Log.i(TAG, "network regained during on-panel sign-in — leaving the live sign-in page intact")
            return
        }
        if (web == null) {
            val waitMs = (SystemClock.elapsedRealtime() - waitingStartedAt).coerceAtLeast(0L)
            if (waitingStartedAt > 0L) Config(this).setLastNetworkWaitMs(waitMs)
            waitingStartedAt = 0L
            Log.i(TAG, "network became available during startup after ${waitMs}ms — creating dashboard WebView")
            waitingStatus = null
            waitingStage = null
            waitingProgress = null
            main.removeCallbacks(waitingTick)
            retryPolicy.reset()
            if (homeDashboardCheckingOwner != null) {
                invalidateHomeDashboardResolution(resetRetry = false)
            }
            buildAndLoad(Config(this))
            if (!screenAwake) scheduleDarkSettle()
            return
        }
        if (frontendConnected || !screenAwake) return // frozen page reconnects itself on wake
        Log.i(TAG, "network regained while frontend not connected — reloading immediately")
        retryPolicy.reset() // a changed environment deserves a fresh fast cadence if HA is still starting
        doReload("network regained")
    }

    /** Pull-to-refresh: a full `WebView.reload()` re-boots the entire frontend app (seconds of blank),
     *  which is overkill for a healthy page — ask the running frontend to re-navigate to the current
     *  view over the external bus instead (instant; JS bundle + websocket stay live). The full reload is
     *  kept for the case that actually needs it: a frontend that isn't connected. */
    private fun lightRefresh() {
        val w = web
        if (w == null || !frontendConnected) { doReload("pull-to-refresh (frontend not connected)"); return }
        // Pull twice in quick succession = full hard reload (the browser's reload-vs-hard-reload
        // pattern, no extra UI): the light navigate re-renders from the frontend's in-memory config,
        // which can't pick up a YAML-mode dashboard edit — a second deliberate pull escalates.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLightRefreshAt < HARD_REFRESH_WINDOW_MS) {
            lastLightRefreshAt = 0L
            doReload("pull-to-refresh (hard, double-pull)")
            return
        }
        lastLightRefreshAt = now
        val path = runCatching { android.net.Uri.parse(w.url).path }.getOrNull().orEmpty()
            .ifBlank {
                resolvedHomeDashboard(Config(this)) ?: run {
                    Log.i(TAG, "pull-to-refresh ignored — no page route and the home dashboard is not resolved yet")
                    swipe?.isRefreshing = false
                    return
                }
            }
        Log.i(TAG, "pull-to-refresh -> light navigate ($path)")
        sendBusNavigate(path)
        // No page-load events fire for a bus navigate — clear the spinner after a short beat.
        val generation = rendererGeneration
        main.postDelayed({ if (rendererCurrent(generation, w)) swipe?.isRefreshing = false }, LIGHT_REFRESH_SPINNER_MS)
    }

    private fun sendBusNavigate(path: String) {
        noteDeliberateDashboardNavigation()
        noteAppNavigationTarget(path)
        val session = externalBusSession ?: return
        dispatchBus(externalBus.navigate(session, path))
    }

    /** Every navigation that CHOOSES a target — bus navigates (user, MQTT, idle return, correction),
     *  full loads of a chosen URL, and navigation observed inside the frontend itself — bumps this.
     *  Mechanical reloads of the same target do not. The async cache correction compares against it
     *  so it can never overwrite a navigation that arrived after the provisional page was chosen. */
    private fun noteDeliberateDashboardNavigation() {
        dashboardNavigationEpoch++
    }

    /** Record what the app itself last aimed the page at, so frontend-observed navigation to the
     *  SAME dashboard (our own load echoing back, or a redirect within it) is not mistaken for the
     *  user going somewhere else. */
    private fun noteAppNavigationTarget(path: String) {
        appNavigatedDashboardRoute = path
    }

    /** True when a frontend-observed location names a different dashboard from the one the app last
     *  aimed at — i.e. genuine user/SPA navigation away from it. An unparseable or same-root
     *  location is deliberately NOT treated as foreign: over-counting would cancel legitimate
     *  corrections, and the correction is a convenience while being pulled off a page is not. */
    private fun observedDashboardNavigationIsForeign(url: String): Boolean {
        val observed = runCatching { android.net.Uri.parse(url).path }.getOrNull() ?: return false
        val claimed = appNavigatedDashboardRoute ?: return false
        // Once the frontend has connected, the page belongs to the user: ANY route change is their
        // choice, including a view change inside the same dashboard (/alpha -> /alpha/view-2),
        // which a root-only comparison silently allowed a delayed correction to overrule. Before the
        // first connection the page is still OUR load settling — the frontend normalising to a
        // default view, or a server redirect — so only a different dashboard root counts, otherwise
        // every launch would cancel its own correction.
        val foreign = if (frontendConnected) {
            HomeDashboardLaunchCache.sameDashboardRoute(observed, claimed).not()
        } else {
            HomeDashboardLaunchCache.dashboardRootOf(observed) != HomeDashboardLaunchCache.dashboardRootOf(claimed)
        }
        if (foreign) appNavigatedDashboardRoute = observed
        return foreign
    }

    /** The only app→frontend external-bus evaluation site. Commands are produced by the typed
     * controller, correlated to this document, and given a one-shot timeout callback. */
    private fun dispatchBus(command: ExternalBusController.Outbound?) {
        command ?: return
        if (!externalBus.owns(command.session) || !rendererCurrent(command.session.rendererGeneration)) return
        val view = web ?: return
        command.evictedIds.forEach { id -> busTimeouts.remove(id)?.let(main::removeCallbacks) }
        val timeout = Runnable {
            busTimeouts.remove(command.id)
            handleBusCompletion(command.session, externalBus.onTimeout(command.session, command.id))
        }
        busTimeouts[command.id] = timeout
        view.evaluateJavascript(command.script, null)
        main.postDelayed(timeout, externalBus.commandTimeoutMs)
    }

    private fun handleBusCompletion(
        session: ExternalBusController.Session,
        completion: ExternalBusController.Completion,
    ) {
        if (!completion.matched || !externalBus.owns(session)) return
        completion.id?.let { id -> busTimeouts.remove(id)?.let(main::removeCallbacks) }
        val kind = when (completion.kind) {
            is ExternalBusController.CommandKind.Navigate -> "navigate"
            is ExternalBusController.CommandKind.KioskMode -> "kiosk_mode/set"
            null -> "unknown"
        }
        val error = completion.error?.let { " error=${it.code.orEmpty()}:${it.message.orEmpty()}" }.orEmpty()
        Log.d(TAG, "bus result command=$kind success=${completion.success}$error")
        dispatchBus(completion.followUp)
        if (completion.retryKiosk) {
            main.postDelayed(
                { if (externalBus.owns(session)) dispatchBus(externalBus.retryKiosk(session)) },
                externalBus.kioskRetryDelayMs,
            )
        }
    }

    private fun handleExternalBus(
        incoming: ExternalBusProtocol.Incoming,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!bridgeCurrent(generation, session)) return
        when (incoming) {
            is ExternalBusProtocol.Incoming.ConfigGet ->
                web?.evaluateJavascript(
                    ExternalBusProtocol.configResult(incoming.id, BuildConfig.VERSION_NAME),
                    null,
                )
            ExternalBusProtocol.Incoming.ConfigScreenShow -> runCatching {
                startActivity(
                    Intent(this, ConfigActivity::class.java).putExtra("path", "/configure"),
                )
            }
            is ExternalBusProtocol.Incoming.ConnectionStatus ->
                onConnectionStatus(incoming.event, generation, session)
            ExternalBusProtocol.Incoming.FrontendLoaded ->
                dispatchBus(externalBus.onFrontendLoaded(session))
            ExternalBusProtocol.Incoming.ThemeUpdate -> captureDashboardTheme(generation, session)
            is ExternalBusProtocol.Incoming.Result ->
                handleBusCompletion(session, externalBus.onResult(session, incoming))
            is ExternalBusProtocol.Incoming.Malformed ->
                Log.w(TAG, "ignored malformed external-bus message (${incoming.reason})")
            is ExternalBusProtocol.Incoming.Unknown -> Unit
        }
    }

    /** Idle return-to-home (opt-in): after the configured minutes with no touch, swap the frontend back
     *  to the home dashboard — a bus navigate with replace, so it's instant and keeps history flat. */
    private fun onIdleCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (!screenAwake || !frontendConnected || authLatched) return
        val config = Config(this)
        val minutes = config.dashboardIdleReturnMin
        val tick = DashboardIdleReturnPolicy.tick(
            minutes = minutes,
            idleMs = SystemClock.elapsedRealtime() - lastTouchAt,
            homeDashboard = { resolvedHomeDashboard(config) },
        ) { home ->
            val current = runCatching { android.net.Uri.parse(web?.url) }.getOrNull()
            DashboardIdleReturnPolicy.target(
                currentPath = current?.path.orEmpty(),
                currentQuery = current?.encodedQuery,
                currentFragment = current?.fragment,
                homeDashboard = home,
            )
        }
        if (idleUnresolvedNotice.shouldLog(tick)) {
            Log.w(TAG, "idle ${minutes}min — home dashboard not resolved yet; idle return waits for it")
        }
        if (tick !is DashboardIdleReturnPolicy.Tick.Return) return
        Log.i(TAG, "idle ${minutes}min — returning to home dashboard (/${tick.target})")
        sendBusNavigate(tick.target)
    }

    /** Register once and return whether Android already has a default network. When false, onCreate
     *  holds the WebView back entirely: loading HA's cached shell while offline only exposes HA's own
     *  long connection-failed countdown on top of Chromium's main-frame failure. */
    private fun registerNetworkCallback(): Boolean {
        conn = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val initiallyAvailable = conn?.activeNetwork != null
        networkRecovery = NetworkRecoveryGate(initiallyAvailable)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { onNetworkAvailable() } }
            override fun onLost(network: Network) { runOnUiThread {
                networkRecovery?.onLost()
            } }
        }
        netCallback = cb
        // One default-network callback for the activity's lifetime (unregistered in onDestroy) — never a
        // per-load registration, which would hit Android's per-app callback limit on a forever process.
        val registered = runCatching {
            val manager = conn ?: error("ConnectivityManager unavailable")
            manager.registerDefaultNetworkCallback(cb)
        }.onFailure { Log.w(TAG, "network callback register failed: ${it.message}") }.isSuccess
        // Never strand on the waiting screen if this OEM cannot register the callback: fall back to the
        // existing WebView watchdog path, which can still recover by polling loads.
        return initiallyAvailable || !registered
    }

    /** One quiet startup state while Android brings networking up. This is native rather than cached
     *  WebView content, so neither Chromium's error UI nor HA's 60-second reconnect page can appear. */
    private fun showWaitingForNetwork() {
        detachLifecycleBar()
        val config = Config(this)
        waitingEstimateLearned = config.lastNetworkWaitMs > 0L
        waitingEstimateMs = config.lastNetworkWaitMs.takeIf { it > 0L } ?: DEFAULT_NETWORK_WAIT_MS
        val surface = statusSurface(config)
        surface.setBrandCaption(getString(R.string.service_running_caption))
        val stage = surface.heading(localizedStartupNetworkStage(startupNetworkSnapshot()))
        val progress = surface.progress()
        val status = surface.caption("")
        surface.setBody(
            surface.caption(getString(R.string.panel_service_running)),
            stage,
            progress,
            surface.detail(getString(R.string.ha_opens_automatically), accent = true),
            status,
        )
        showStatusSurface(surface) { showWaitingForNetwork() }
        waitingStatus = status
        waitingStage = stage
        waitingProgress = progress
        // Redrawing after a rotation must not restart the clock — the panel has been waiting exactly
        // as long as it was a moment ago, and the learned estimate is measured from this.
        if (waitingStartedAt == 0L) waitingStartedAt = SystemClock.elapsedRealtime()
        // Cancel before starting: a redraw used to leave the previous once-per-second chain running,
        // so every configuration change added a permanent timer until the network recovered.
        main.removeCallbacks(waitingTick)
        waitingTick.run()
        Log.i(TAG, "no default network at startup — waiting before creating WebView")
    }

    /** Best-effort portable view of pre-default-network progress. Ethernet carrier comes from sysfs
     * when readable; interface/address state uses java.net and works without privileged APIs. */
    private fun startupNetworkSnapshot(): StartupNetworkSnapshot {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filterNot { it.isLoopback }
        }.getOrDefault(emptyList())
        val candidates = interfaces.filter {
            it.name.startsWith("eth") || it.name.startsWith("en") || it.name.startsWith("wlan")
        }
        val linkUp = candidates.any { iface ->
            val carrier = if (iface.name.startsWith("eth") || iface.name.startsWith("en")) {
                runCatching { File("/sys/class/net/${iface.name}/carrier").readText().trim() == "1" }.getOrNull()
            } else null
            carrier ?: runCatching { iface.isUp }.getOrDefault(false)
        }
        val addressAssigned = candidates.any { iface ->
            Collections.list(iface.inetAddresses).any { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress
            }
        }
        return StartupNetworkSnapshot(
            interfacePresent = candidates.isNotEmpty(),
            linkUp = linkUp,
            addressAssigned = addressAssigned,
            defaultNetwork = conn?.activeNetwork != null,
        )
    }

    private fun localizedStartupNetworkStage(snapshot: StartupNetworkSnapshot): String = getString(
        when {
            !snapshot.interfacePresent -> R.string.network_starting_android
            !snapshot.linkUp -> R.string.network_waiting_link
            !snapshot.addressAssigned -> R.string.network_waiting_address
            !snapshot.defaultNetwork -> R.string.network_preparing_connection
            else -> R.string.network_opening_ha
        },
    )

    /** Re-load the dashboard: a plain reload normally, but a fresh loadUrl of the real dashboard when
     *  the WebView is currently showing the "Reconnecting…" interstitial (reloading THAT would just
     *  re-show the interstitial forever). */
    private fun reloadTarget(): Boolean {
        val w = web ?: return false
        val config = Config(this)
        val fresh = retryNeedsFreshLoad(committedPageUrl, config.haUrl, interstitialShown, dashboardRenderer = signInShownForUrl == null)
        // Only a fresh load needs the home dashboard; unresolved, admission loads it instead.
        val home = if (fresh) {
            resolvedHomeDashboard(config) ?: run {
                readmitForHomeDashboard("reload over the reconnecting page")
                return false
            }
        } else null
        if (!rotateBusDocument(w, config, rendererGeneration)) return false
        if (home != null) {
            interstitialShown = false
            val target = currentUrl(config, home)
            expectPageStart(target)
            w.loadUrl(target)
        } else {
            w.url?.let(::expectPageStart)
            w.reload()
        }
        return true
    }

    /** Reload + arm the handshake watchdog (only fires while awake) — for user/health-driven reloads. */
    private fun doReload(reason: String) {
        Log.i(TAG, "reload: $reason")
        if (!reloadTarget()) return
        onLoadStarted()
    }

    /** Reload without arming the watchdog — for screen-off memory reloads, where the settle timer (not the
     *  watchdog) governs when the page freezes, so the watchdog can't turn a dark reload into a loop. */
    private fun doReloadNoWatchdog(reason: String) {
        Log.i(TAG, "reload: $reason")
        frontendConnected = false
        clearedThisLoad = false
        if (!reloadTarget()) return
    }

    /** A main-frame load failed (HA down / network out / DNS): replace Android's native gray error page
     *  with a branded dark "reconnecting" screen while the handshake watchdog keeps retrying behind it.
     *  The native error page can't be styled or suppressed any other way, and a wall panel showing
     *  `net::ERR_CONNECTION_REFUSED` between retries reads as broken rather than waiting. */
    /** Draw the reconnect page again, e.g. after a theme change while it is the visible surface. */
    private fun reissueReconnecting() {
        interstitialShown = false
        showReconnecting(lastInterstitialDetail)
    }

    private fun showReconnecting(detail: String) {
        if (destroyed || authLatched || interstitialShown) return
        interstitialShown = true
        lastInterstitialDetail = detail
        Log.w(TAG, "main-frame load error ($detail) — showing reconnecting page; watchdog retries continue")
        val dark = StatusSurface.darkFor(this, Config(this))
        val palette = statusPalette(dark)
        val reconnecting = android.text.TextUtils.htmlEncode(getString(R.string.dashboard_reconnecting))
        val retrying = android.text.TextUtils.htmlEncode(getString(R.string.dashboard_unreachable_retry))
        web?.let(::suspendBusDocument)
        web?.loadDataWithBaseURL(
            null,
            """<!doctype html><html><body style="background:${palette.background};color:${palette.body};
               font-family:sans-serif;display:flex;align-items:flex-start;justify-content:center;
               min-height:100vh;margin:0;padding:6vh 0;box-sizing:border-box">
               <div style="max-width:80%;text-align:center">
               ${statusBrandHtmlHeader(this, dark, palette)}
               <h1>$reconnecting</h1>
               <p style="font-size:1.2em;color:${palette.subtle}">$retrying</p>
               <p style="color:${palette.subtle}"><small>$detail</small></p>
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    /** The URL to show: a pending navigate path (consumed — one-shot, so crash rebuilds and
     *  interstitial recoveries return to home rather than replaying a stale navigate), else the
     *  resolved [home] dashboard. */
    private fun currentUrl(config: Config, home: String): String = ExternalAuthProtocol.dashboardUrl(
        config.haUrl,
        BuiltinDashboard.consumeNavPath() ?: home,
    )

    private fun resolvedHomeDashboard(config: Config): String? =
        ownedHomeDashboardPath(homeDashboardResolution, homeDashboardOwner(config))

    /** A load of the home dashboard was asked for while none is resolved for the current owner: hand the
     *  panel back to admission, which resolves it (launch cache first, then live) and loads home itself.
     *  The same teardown-and-rebuild an authority change takes. */
    private fun readmitForHomeDashboard(why: String) {
        Log.i(TAG, "$why before the home dashboard resolved — re-entering admission")
        teardownWeb()
        buildAndLoad(Config(this))
    }

    // Publish foreground state so SystemController.dashboardState can drive the watchdog + kiosk
    // return-loop from an in-process signal instead of a root pidof/dumpsys probe.
    //
    // The precise signal is TOP-RESUMED, not focus or resume/pause. On some OEM launchers (verified on
    // rk3576/Android-14 quickstep) opening Overview/Recents leaves the dashboard both window-FOCUSED and
    // RESUMED behind a translucent overlay — so onPause and onWindowFocusChanged never fire — yet the
    // dashboard is no longer the top activity the user is on. onTopResumedActivityChanged (API 29+) is
    // the lifecycle form of dumpsys `topResumedActivity` and flips exactly on that transition.
    // onResume/onPause are the API<29 baseline (older panels lack the callback; their launchers also
    // predate translucent Overview, so resume/pause suffices there).
    override fun onResume() {
        super.onResume()
        if (maintenanceFence.stop(this)) return
        NativeLocale.apply(Config(this).uiLanguage)
        // Below API 29 onTopResumedActivityChanged is never delivered, so resume owns visibility there.
        if (resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) onAdmissionVisibilityChanged(true)
        BuiltinDashboard.setActivityForeground(activityOwner, true)
        if (::activityConfig.isInitialized) applyRendererScreenPolicy()
        applyFullscreen()
        applyOverscroll()
        applyZoom()
        // Reconcile the outage card with the canonical clock on wake: `postDelayed` runs on uptime,
        // which pauses through deep sleep while the canonical window does not, so a recovery notice
        // that lapsed during sleep would otherwise stay visible until its stalled timer caught up.
        redrawLifecycleBar()
    }

    /** Camera trial: request CAMERA only when [Config.cameraEnabled] is already on and the
     *  permission is not already held. Called both from the settings-change listener above (the switch
     *  flips while this activity is resumed) and from [onResume] (the switch flipped while backgrounded). */
    private fun requestCameraPermissionIfNeeded() {
        // Camera trial: whether to ask is the session owner's decision — it alone knows the profile
        // declares a camera, the switch is on and the permission is missing — and a denial is remembered
        // durably. Reaching here means the delivery gate has seen the lifecycle's ON_RESUME, so the
        // dialog can be raised; a guard on the lifecycle state here would reject a signal that lands
        // during onResume itself and never retry it.
        if (!io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.shouldAsk()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.asking()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.answered(granted)
        Log.d(TAG, "camera permission ${if (granted) "granted" else "denied"}")
    }

    /** The foreground built-in renderer owns its timeout policy directly. This is the standard Android
     * activity mechanism and does not interfere with explicit ha-paneld screen-off brightness/bl_power. */
    private fun applyRendererScreenPolicy() {
        if (shouldKeepBuiltInRendererScreenOn(activityConfig.preventIdleDim)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Android's overscroll stretch (12+) / edge-glow (older) when a drag runs past the top or bottom
     *  of the page. Off by default on a wall panel; the hidden `dashboard_overscroll` API setting turns
     *  it back on. Re-read + applied on resume so a live config change lands on the foreground relaunch. */
    private fun applyOverscroll() {
        web?.overScrollMode =
            if (Config(this).dashboardOverscroll) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_NEVER
    }

    /** Page zoom (%). Re-read + applied on resume so a live `dashboard_zoom` change lands; the POST
     *  path also reloads the page, since a fresh load is where the initial scale reliably takes effect. */
    private fun applyZoom() {
        web?.setInitialScale((resources.displayMetrics.density * Config(this).dashboardZoom).toInt())
    }

    /**
     * Edge-to-edge dashboard (issue #25): hide the Android status + navigation bars while the dashboard is
     * up. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE keeps the admin escape — a swipe from a screen edge
     * reveals the bars briefly — so this can never strand anyone. Now that the dashboard is our own
     * activity this is plain app-level immersive (no root, unlike suppressing bars for a foreign
     * renderer). Re-asserted on resume and on regaining window focus, because the system restores bars
     * after transient reveals and some dialogs. Toggle: Configure → Built-in renderer → "Hide Android system bars".
     */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Config(this).dashboardFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (maintenanceFence.stop(this)) return
        if (hasFocus) applyFullscreen()
    }
    override fun onPause() {
        onAdmissionVisibilityChanged(false)            // the retry stays armed; only the repaint stops
        BuiltinDashboard.setActivityForeground(activityOwner, false)
        super.onPause()
    }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (maintenanceFence.stop(this)) return
        if (!resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) {
            onAdmissionVisibilityChanged(isTopResumedActivity)
        }
        BuiltinDashboard.setActivityForeground(activityOwner, isTopResumedActivity)
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
    private fun applyForceDark(w: WebView) = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) return@runCatching
        val dark = if (android.os.Build.VERSION.SDK_INT >= 29) {
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

    /** configChanges declares uiMode, so a system dark/light flip lands here instead of recreating the
     *  activity — re-mirror it into the 10..12 force-dark flag (the frontend re-themes live off the
     *  resulting prefers-color-scheme change; no reload). No-op below 10 (config-driven) and on 13+. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (maintenanceFence.stop(this)) return
        if (android.os.Build.VERSION.SDK_INT in 29..32) web?.let { applyForceDark(it) }
        // This activity handles orientation, screen size and night mode itself rather than being
        // recreated, so a status screen that is up stays as drawn unless it is redrawn here — but only
        // when something it depends on actually moved. It also receives locale, keyboard and font-scale
        // events, and rebuilding a live surface for those is churn a user can see.
        val spec = StatusSurface.specFor(this)
        val dark = StatusSurface.darkFor(this, Config(this))
        val current = statusSurface
        if (current == null || !statusSurfaceReusable(current.spec, current.dark, spec, dark)) {
            convergeStatusTheme()
        }
    }

    /**
     * The one branded frame every status screen in this activity is drawn on, kept across phases.
     *
     * Reusing the instance is what makes the mark stationary: a phase change replaces the body and
     * leaves the header view untouched, so it cannot move, reflow or blink between, say, "Checking
     * Home Assistant compatibility" and the verdict that follows it a second later.
     */
    private var statusSurface: StatusSurface? = null

    /**
     * How to draw the status screen that is currently up, so a configuration change can redraw it.
     *
     * Rebuilding on next use is enough for a screen that is about to change anyway, and wrong for one
     * that is not: the compatibility verdicts and the entity holds are static, so a rotation or a
     * day/night switch left them showing the previous shape's geometry, palette and artwork until
     * something else happened to move them on — which, on a hold, may be never.
     */
    private var statusRerender: (() -> Unit)? = null
    /** The detail last shown on the reconnect page, so a theme change can redraw it unchanged. */
    private var lastInterstitialDetail: String = ""


    private fun statusSurface(config: Config = Config(this)): StatusSurface {
        val dark = StatusSurface.darkFor(this, config)
        val spec = StatusSurface.specFor(this)
        statusSurface?.let { if (statusSurfaceReusable(it.spec, it.dark, spec, dark)) return it }
        return StatusSurface(this, dark).also { statusSurface = it }
    }

    /**
     * Install the frame only when it is not already installed.
     *
     * Caching the instance is not enough on its own: `setContentView` clears the content parent
     * before adding, so handing it the same root again detaches and reattaches the header the frame
     * exists to hold still. This activity declares `orientation|screenSize|uiMode` in its
     * `configChanges`, so it also survives rotations that would otherwise have recreated it.
     */
    private fun showStatusSurface(surface: StatusSurface, rerender: () -> Unit) {
        // Every native status screen is installed here, so this is also the one place that has to
        // disarm the admission auto-retry: a screen that has been replaced must not keep a timer
        // running against the view tree it left behind. A caller that means to keep its pending retry
        // reads the remaining time first and re-arms it afterwards.
        cancelAdmissionAutoRetry()
        statusRerender = rerender
        if (statusSurfaceAlreadyInstalled(root, surface.root)) return
        root = surface.root
        setContentView(surface.root)
    }

    /** A blocked admission screen. [outcome] is not defaulted: every blocked screen must say what the
     *  panel learned, and [admissionRetryClass] — not this call site — decides whether that recovers on
     *  its own. Progress screens use [showAdmissionProgressScreen] instead.
     *
     *  This is also the single site that publishes a blocked verdict to [RendererAdmissionRuntime], so
     *  a screen cannot reach the panel without the diagnostic surfaces learning about it. [evidence] is
     *  the classified transport failure where one was observed; the raw [detail] stays on the panel and
     *  never enters the runtime, because it can embed the configured Home Assistant host. */
    private fun showBlockedAdmissionScreen(
        title: String,
        detail: String,
        outcome: AdmissionOutcome,
        evidence: HaTransportEvidence = HaTransportEvidence.NONE,
    ) {
        RendererAdmissionRuntime.record(
            owner = activityOwner,
            state = RendererAdmissionState.BLOCKED,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            outcome = outcome,
            evidence = evidence,
        )
        showV2CompatibilityScreen(title, detail, getString(R.string.retry), admissionRetryClass(outcome), outcome)
    }

    /** A screen that reports work in flight; it is replaced by that work's outcome, so it never arms. */
    private fun showAdmissionProgressScreen(title: String, detail: String) {
        RendererAdmissionRuntime.record(
            owner = activityOwner,
            state = RendererAdmissionState.CHECKING,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        showV2CompatibilityScreen(title, detail, null, AdmissionRetryClass.MANUAL_ONLY, null)
    }

    private fun showV2CompatibilityScreen(
        title: String,
        detail: String,
        retryLabel: String?,
        autoRetry: AdmissionRetryClass,
        outcome: AdmissionOutcome?,
    ) = paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, rearm = true)

    /**
     * [rearm] is false only when the screen already on display is being redrawn for a panel-theme or
     * system configuration change. The retry deadline and the back-off ladder belong to the admission
     * failure, not to the drawing of it: re-arming on every repaint would advance the ladder and push
     * the deadline further out each time, so a panel being rotated could postpone its own recovery
     * indefinitely. The pending deadline is therefore carried across the redraw unchanged, while the
     * countdown row itself is rebuilt because the previous one belongs to a discarded view tree.
     */
    private fun paintV2CompatibilityScreen(
        title: String,
        detail: String,
        retryLabel: String?,
        autoRetry: AdmissionRetryClass,
        outcome: AdmissionOutcome?,
        rearm: Boolean,
    ) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        // Read before the shared installer disarms, and read as an INSTANT: everything between here
        // and the re-arm below — building the views, laying them out, installing them — is time that a
        // carried duration would add to the deadline on every single repaint.
        val carriedDeadlineMs = if (rearm) null else admissionCountdown.deadlineAtMs
        if (web != null) teardownWeb()
        val surface = statusSurface()
        // What this particular screen can offer beyond Retry, decided from the verdict it is showing
        // rather than from the call site's copy. Both answers are pure and tested away from the views.
        val repair = webViewRepairOffer(outcome, WebViewRepairRuntime.capability())
        val phoneUrl = configureQrPath(outcome)?.let { path ->
            scannableHost(localIpv4(), localIpv6())?.let { host ->
                LocalAdminEndpoint.url(host, Config(this).httpPort, path)
            }
        }
        val repairNote = webViewRepairNote(repair)?.let { surface.detail(it) }
        val qr = phoneUrl?.let { surface.qr(it, getString(R.string.config_qr_description, it)) }
        val phoneAddress = phoneUrl?.takeIf { qr != null }?.let { surface.caption(it, accent = true) }
        // A code replaces the explanation as well as the button, because at this size it cannot sit
        // under the full one without pushing the action off the panel. Measured, not assumed.
        val shown = (if (qr != null) localizedConfigureQrDetail(outcome) else null) ?: detail
        val buttons = buildList {
            retryLabel?.let { label ->
                add(surface.action(label) {
                    retryAdmission(resetBackoff = true)
                })
            }
            if (repair == WebViewRepairOffer.OFFER) {
                add(surface.action(getString(R.string.update_web_viewer), primary = true) { button ->
                    startWebViewRepair(button, repairNote)
                })
            }
            // The code replaces Configure rather than joining it: both open the same page, and the
            // whole reason the code is here is that this page is the one worth doing on a phone.
            if (qr == null) {
                add(surface.action(getString(R.string.configure)) {
                    startActivity(Intent(this@DashboardActivity, ConfigActivity::class.java).putExtra("path", "/configure"))
                })
            }
        }
        // The retry countdown is deliberately a real number under the actions, not a spinner. The
        // jittered delay is computed once, here, and this row counts down to that exact figure. A
        // redraw computes nothing: it already has a deadline, and only the ROW needs rebuilding.
        val autoRetryDelayMs = if (carriedDeadlineMs != null) null
            else if (retryLabel == null) null else admissionRetryPolicy.nextDelayMs(autoRetry)
        val countdown = if (carriedDeadlineMs != null || autoRetryDelayMs != null) surface.caption("") else null
        surface.setBody(
            *listOfNotNull(
                surface.heading(title),
                surface.detail(shown),
                qr,
                phoneAddress,
                repairNote,
                surface.actionRow(*buttons.toTypedArray()),
                countdown,
            ).toTypedArray(),
        )
        // The outcome travels with the redraw, so a rotation or a theme flip rebuilds the same offer
        // rather than a bare Retry screen. An in-flight repair is deliberately NOT carried: the poll
        // holds the views it updates, both die with the discarded tree, and the installer's own single
        // slot is still the authority the redrawn screen reads.
        showStatusSurface(surface) {
            paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, rearm = false)
        }
        admissionCountdownView = countdown
        if (carriedDeadlineMs != null) resumeAdmissionAutoRetry(carriedDeadlineMs, title)
        else if (autoRetryDelayMs != null) armAdmissionAutoRetry(autoRetryDelayMs, title)
        Log.w(TAG, "$title: $detail")
    }

    /**
     * What the screen says about repairing itself, in the person's terms rather than the installer's.
     *
     * The copy lives here rather than beside the decision so that it stays inside the file the plain
     * language gate reads; the decision it renders is pure and tested on its own.
     */
    private fun webViewRepairNote(offer: WebViewRepairOffer): String? = when (offer) {
        WebViewRepairOffer.OFFER ->
            getString(R.string.web_view_repair_known_good)
        WebViewRepairOffer.NEEDS_PRIVILEGE ->
            getString(R.string.web_view_repair_needs_privilege)
        WebViewRepairOffer.NO_KNOWN_GOOD_BUILD ->
            getString(R.string.web_view_repair_no_build)
        WebViewRepairOffer.MANAGED_ELSEWHERE ->
            getString(R.string.web_view_repair_managed_elsewhere)
        // Nothing extra to say in either case: one is a screen about something else, and the other
        // is a screen whose panel has not finished working out what it can do.
        WebViewRepairOffer.NOT_REPAIRABLE,
        WebViewRepairOffer.UNKNOWN_CAPABILITY,
        -> null
    }

    private fun localizedConfigureQrDetail(outcome: AdmissionOutcome?): String? = when (outcome) {
        AdmissionOutcome.SIGN_IN_REQUIRED -> getString(R.string.ha_sign_in_needed_qr)
        AdmissionOutcome.CREDENTIAL_REFUSED -> getString(R.string.ha_sign_in_rejected_qr)
        else -> null
    }

    /**
     * Run the repair, and report it where the person is already looking.
     *
     * **There is no success branch, and its absence is the design.** Installing an engine is only half of
     * the repair: a provider binds once per process, so the panel cannot use what it just installed until
     * it restarts, and the service takes that restart a few seconds later. The ordinary end of a
     * successful repair is therefore this screen disappearing with the process that drew it, and the
     * panel returning through the normal admission sequence. Everything below describes the paths where
     * nothing was installed.
     */
    private fun startWebViewRepair(button: Button, note: TextView?) {
        button.isEnabled = false
        button.setText(R.string.installing)
        when (WebViewRepairRuntime.request()) {
            WebViewRepairRequest.STARTED -> {
                note?.setText(R.string.downloading_installing)
                pollWebViewRepair(button, note)
            }
            // Both refusals hand the button back, because both are states that lapse: another install
            // finishes, and a service that has not attached yet attaches. A disabled button here would
            // be a dead end on a screen whose whole purpose is to stop being one.
            WebViewRepairRequest.BUSY -> releaseWebViewRepair(
                button,
                note,
                getString(R.string.install_busy),
            )
            WebViewRepairRequest.UNAVAILABLE -> releaseWebViewRepair(
                button,
                note,
                getString(R.string.panel_service_not_ready_install),
            )
        }
    }

    /** Hand the offer back after an attempt that installed nothing, saying why. */
    private fun releaseWebViewRepair(button: Button, note: TextView?, reason: String) {
        button.isEnabled = true
        button.setText(R.string.update_web_viewer)
        note?.text = reason
    }

    private fun localizedHaUnavailableDetail(blocked: DashboardV2ProbeResult.Unavailable): String =
        getString(
            R.string.cannot_reach_ha_detail,
            localizedHaTransportFault(blocked.evidence.fault),
            blocked.detail,
        )

    private fun localizedWebViewRepairFailure(progress: WebViewRepairProgress): String {
        val explanation = when (webViewRepairFailureKind(progress)) {
            WebViewRepairFailureKind.NO_RECOMMENDATION -> getString(R.string.web_view_repair_no_build)
            WebViewRepairFailureKind.NO_CHANGE -> getString(R.string.web_view_repair_no_change)
            WebViewRepairFailureKind.NO_INSTALL_ROUTE -> getString(R.string.web_view_repair_no_install_route)
            WebViewRepairFailureKind.DOWNLOAD -> getString(R.string.web_view_repair_download_failed)
            WebViewRepairFailureKind.DOWNLOAD_TOO_LARGE -> getString(R.string.web_view_repair_download_too_large)
            WebViewRepairFailureKind.RETRYABLE -> getString(R.string.web_view_repair_retryable)
            WebViewRepairFailureKind.STORAGE -> getString(R.string.web_view_repair_storage_failed)
            WebViewRepairFailureKind.STAGING -> getString(R.string.web_view_repair_staging_failed)
            WebViewRepairFailureKind.DEFERRED -> getString(R.string.web_view_repair_deferred)
            WebViewRepairFailureKind.REJECTED -> getString(R.string.web_view_repair_rejected)
            WebViewRepairFailureKind.CANCELLED -> getString(R.string.web_view_repair_cancelled)
            null -> return progress.message.takeIf { it.isNotBlank() }
                ?: getString(R.string.update_stopped_unknown)
        }
        return progress.message.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.web_view_repair_failure_detail, explanation, it) }
            ?: explanation
    }

    /**
     * Follow the running install and retitle the note as it goes.
     *
     * The installer publishes one process-wide slot and no event, so this reads it on a timer — the same
     * shape the Install page uses over HTTP. Both views are re-tested for attachment on every tick, which
     * is this activity's existing idiom for a callback that outlives the screen it was created for: a
     * replaced screen's poll finds a detached button and stops, without a teardown hook to forget.
     */
    private fun pollWebViewRepair(button: Button, note: TextView?) {
        main.postDelayed({
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return@postDelayed
            if (!button.isAttachedToWindow) return@postDelayed
            val progress = WebViewRepairRuntime.progress()
            // A RUNNING install is deliberately not narrated by the installer's own words. Measured on a
            // panel: it publishes the placeholder "Working…" for the whole download and never updates it,
            // because the installer reports one terminal string and has no progress callback. Adopting
            // that would replace a sentence that tells somebody what is happening and roughly how long
            // with a word that tells them neither.
            if (progress.running) {
                pollWebViewRepair(button, note)
                return@postDelayed
            }
            // Nothing running and this screen is still here, so the engine was not replaced — replacing
            // it takes this process with it. An EMPTY message is not a failure report: the installer's
            // slot lives in this process and a restart clears it, so blank means "no information", and
            // the honest sentence for that is not "it failed".
            releaseWebViewRepair(
                button,
                note,
                localizedWebViewRepairFailure(progress),
            )
        }, WEB_VIEW_REPAIR_POLL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndLoad(config: Config) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        // Sign-in is checked FIRST, ahead of the entity-bootstrap hold. The hold derives only from the
        // learning/filter flags, so on a panel with entity learning enabled and no credential yet it is
        // entered unconditionally and never left — the bootstrap it waits for needs an authenticated
        // Home Assistant connection that only the sign-in below can produce. Ordered the other way this
        // is a second, quieter deadlock sitting directly behind the readiness gate.
        val url = config.haUrl.trim().trimEnd('/')
        if (haSignInPending(url, config.haToken, config.haRefreshToken)) {
            showPhysicalHaSignIn(url)
            return
        }
        // Held until the wizard's entity-filter question is answered. Placed AFTER sign-in (which mints the
        // credential this hold requires) and BEFORE any WebView is created, because the whole purpose is that
        // the panel's first render is already the filtered one — loading unfiltered and reloading afterwards
        // is the slow, laggy first impression this prevents.
        if (entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = url,
                haToken = config.haToken,
                haRefreshToken = config.haRefreshToken,
                entityFilterAnswered = config.setupEntityFilterAnswered,
                setupEverCompleted = config.setupEverCompleted,
                entityFilterEnabled = config.dashboardEntityLearningEnabled,
            )
        ) {
            showWaitingForEntityFilterAnswer()
            return
        }
        if (holdForEntityBootstrap(config) || entityFilterNativeHold != null) {
            showWaitingForEntityBootstrap()
            return
        }
        val owner = DashboardV2CompatibilityOwner(url, config.haAuthSnapshot().stableOwner())
        if (secureBridgeAdmission() == AdmissionOutcome.BRIDGE_UNAVAILABLE) {
            showBlockedAdmissionScreen(
                getString(R.string.web_viewer_too_old_title),
                getString(R.string.web_viewer_too_old_detail),
                AdmissionOutcome.BRIDGE_UNAVAILABLE,
            )
            return
        }
        if (compatibilityReadyUrl == url) {
            resolveHomeDashboardAndLoad(config)
            return
        }
        if (compatibilityCheckingOwner == owner && compatibilityJob?.isActive == true) return
        compatibilityJob?.cancel()
        val compatibilityTicket = compatibilityAttempts.start(owner)
        compatibilityCheckingOwner = owner
        showAdmissionProgressScreen(
            getString(R.string.checking_ha_compatibility),
            getString(R.string.checking_ha_compatibility_detail),
        )
        compatibilityJob = activityScope.launch {
            val result = DashboardV2CompatibilityProbe(
                config,
                stillCurrent = {
                    !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
                        compatibilityAttempts.owns(compatibilityTicket, compatibilityOwner(config))
                },
            ).check()
            val currentConfig = Config(this@DashboardActivity)
            val currentOwner = compatibilityOwner(currentConfig)
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) ||
                !compatibilityAttempts.owns(compatibilityTicket, currentOwner)
            ) return@launch
            compatibilityCheckingOwner = null
            when (val admission = DashboardV2Admission.resolve(result, config.cachedHaServerVersion(url))) {
                is DashboardV2Admission.Compatible -> {
                    // Admitted on a cached version is recorded DISTINCTLY from a live pass. The panel
                    // starts, but nothing about the server was confirmed this time — and when the
                    // page then hits the same wall the check could not cross, the panel is admitted
                    // and blank while every other health signal it has still reads normal.
                    RendererAdmissionRuntime.record(
                        owner = activityOwner,
                        state = RendererAdmissionState.ADMITTED,
                        nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        admittedOnCachedVersion = !admission.live,
                        evidence = (result as? DashboardV2ProbeResult.Unavailable)?.evidence
                            ?: HaTransportEvidence.NONE,
                    )
                    if (admission.live) {
                        config.setHaServerVersionIfOwned(url, admission.version)
                    } else {
                        val detail = (result as DashboardV2ProbeResult.Unavailable).detail
                        Log.w(TAG, "HA version check unavailable; using previously verified ${admission.version} ($detail)")
                    }
                    compatibilityReadyUrl = url
                    v2Handshake.reset()
                    buildAndLoad(currentConfig)
                }
                is DashboardV2Admission.Blocked -> when (val blocked = admission.result) {
                    is DashboardV2ProbeResult.UnsupportedHa -> {
                        config.setHaServerVersionIfOwned(url, blocked.version)
                        showBlockedAdmissionScreen(
                            getString(R.string.ha_upgrade_required),
                            getString(R.string.ha_upgrade_required_detail, blocked.version),
                            AdmissionOutcome.UNSUPPORTED_HA,
                        )
                    }
                    // A degraded proxy or captive-portal response lands here too, so probe again at
                    // the ceiling cadence rather than never.
                    is DashboardV2ProbeResult.Unverifiable -> showBlockedAdmissionScreen(
                        getString(R.string.ha_version_unverifiable),
                        getString(R.string.ha_version_unverifiable_detail, blocked.version?.let { " (“$it”)" }.orEmpty()),
                        AdmissionOutcome.VERSION_UNVERIFIABLE,
                    )
                    // Two different situations, kept as separate outcomes so a diagnostic surface can
                    // tell them apart, but BOTH person-repaired. Neither runs a timer: an absent
                    // credential has nothing to re-ask with, and replaying an unchanged rejected one
                    // every few minutes is what triggers Home Assistant's login-attempt banning, which
                    // would turn a repairable refusal into a locked-out account. Only a resolution
                    // carrying no authentication verdict at all retries by itself.
                    //
                    // The copy below must therefore never promise that the panel keeps checking. It
                    // did between the ceiling-cadence proposal and the review correction that kept
                    // this outcome manual, which left the screen promising a retry that never came.
                    DashboardV2ProbeResult.AuthenticationFailed -> {
                        val neverSignedIn = config.haToken.isBlank() && config.haRefreshToken.isBlank()
                        // Nothing recorded WHY the panel reached this verdict, leaving no way to tell a
                        // refusal from a probe that ran too early.
                        Log.w(
                            TAG,
                            "HA admission refused authentication (neverSignedIn=$neverSignedIn) — " +
                                "no token was accepted for the compatibility check",
                        )
                        showBlockedAdmissionScreen(
                            if (neverSignedIn) {
                                getString(R.string.ha_sign_in_needed)
                            } else {
                                getString(R.string.ha_sign_in_rejected)
                            },
                            if (neverSignedIn) {
                                getString(R.string.ha_sign_in_needed_detail)
                            } else {
                                getString(R.string.ha_sign_in_rejected_detail)
                            },
                            if (neverSignedIn) {
                                AdmissionOutcome.SIGN_IN_REQUIRED
                            } else {
                                AdmissionOutcome.CREDENTIAL_REFUSED
                            },
                        )
                    }
                    is DashboardV2ProbeResult.Unavailable -> showBlockedAdmissionScreen(
                        getString(R.string.cannot_reach_ha),
                        localizedHaUnavailableDetail(blocked),
                        AdmissionOutcome.TRANSPORT_FAILED,
                        blocked.evidence,
                    )
                    is DashboardV2ProbeResult.Compatible -> error("compatible result cannot be blocked")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPhysicalHaSignIn(haUrl: String) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        cancelAdmissionAutoRetry()
        // Already showing sign-in for this exact URL: keep the live WebView. Every config save relaunches
        // this activity, and the browser form posts on each save, so rebuilding here would discard a
        // part-typed Home Assistant password whenever anything else was saved from another device.
        if (signInShownForUrl == haUrl && web != null) {
            Log.i(TAG, "on-panel sign-in already showing for this endpoint — keeping the current screen")
            return
        }
        signInShownForUrl = haUrl
        signInLoadRetries = 0
        if (web != null) teardownWeb()
        val generation = rendererGate.open()
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            setBackgroundColor(BG_DARK)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!rendererCurrent(generation, view)) return true
                    return !panelHaOAuthNavigationAllowed(haUrl, request.url.toString())
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!rendererCurrent(generation, view) || !isPanelHaOAuthCallback(url)) return
                    view.evaluateJavascript(
                        "document.body&&document.body.dataset?document.body.dataset.haOauthStatus:\"\"",
                    ) { result ->
                        if (!rendererCurrent(generation, view)) return@evaluateJavascript
                        if (result?.trim('"') == "success") {
                            main.postDelayed({
                                if (rendererCurrent(generation, view)) {
                                    compatibilityReadyUrl = null
                                    compatibilityCheckingOwner = null
                                    compatibilityAttempts.invalidate()
                                    buildAndLoad(Config(this@DashboardActivity))
                                }
                            }, 750L)
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                    // A main-frame error here is usually TRANSIENT: the sign-in start page is served by
                    // this panel's own local HTTP server, which restarts briefly during an MQTT or config
                    // reconfigure — exactly when a first-run user is mid-setup. On hardware review that
                    // produced a terminal "sign-in unavailable" over a sign-in the user may have been in
                    // the middle of. Retry quietly first; only a persistent failure earns the verdict.
                    if (signInLoadRetries < SIGN_IN_LOAD_RETRIES_MAX) {
                        signInLoadRetries++
                        Log.i(TAG, "panel sign-in page load failed — retry $signInLoadRetries/$SIGN_IN_LOAD_RETRIES_MAX")
                        main.postDelayed({
                            if (rendererCurrent(generation, view) && !destroyed) {
                                view.loadUrl(panelHaOAuthStartUrl(haUrl))
                            }
                        }, SIGN_IN_LOAD_RETRY_MS)
                        return
                    }
                    showBlockedAdmissionScreen(
                        getString(R.string.ha_sign_in_page_failed),
                        getString(R.string.ha_sign_in_page_failed_detail),
                        AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
                    )
                }
            }
        }
        web = view
        val content = FrameLayout(this).apply {
            setBackgroundColor(BG_DARK)
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        // The status frame's redraw belongs to the status frame. Left armed here, a later
        // configuration change replaced this live sign-in WebView with an obsolete status screen and
        // could strand the panel, because the transition that produced that screen will not repeat.
        statusRerender = null
        root = content
        setContentView(content)
        view.loadUrl(panelHaOAuthStartUrl(haUrl))
    }

    private fun compatibilityOwner(config: Config): DashboardV2CompatibilityOwner =
        DashboardV2CompatibilityOwner(
            normalizedUrl = config.haUrl.trim().trimEnd('/'),
            authOwner = config.haAuthSnapshot().stableOwner(),
        )

    private fun homeDashboardOwner(config: Config): HomeDashboardResolutionOwner =
        HomeDashboardResolutionOwner(
            authOwner = config.haAuthSnapshot().stableOwner(),
            configuredPath = config.homeDashboard.trim(),
        )

    private fun invalidateHomeDashboardResolution(resetRetry: Boolean = true) {
        homeDashboardJob?.cancel()
        homeDashboardJob = null
        homeDashboardCheckingOwner = null
        homeDashboardResolution = null
        provisionalHomeDashboardEpoch = null
        homeDashboardAttempts.invalidate()
        if (resetRetry) admissionRetryPolicy.reset()
    }

    /** The one admission retry sequence — the automatic timer and the manual Retry button both run
     *  exactly this, so the two recovery paths cannot diverge. */
    private fun retryAdmission(resetBackoff: Boolean) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        invalidateHomeDashboardResolution(resetRetry = resetBackoff)
        compatibilityCheckingOwner = null
        compatibilityReadyUrl = null
        compatibilityAttempts.invalidate()
        v2Handshake.reset()
        buildAndLoad(Config(this))
    }

    /** Disarm the admission auto-retry and its countdown. Every function that replaces the activity's
     *  content view MUST call this first: the timer belongs to the blocked screen that armed it, and a
     *  survivor firing under a healthy dashboard would tear that dashboard down to re-probe. */
    private fun cancelAdmissionAutoRetry() {
        admissionCountdown.disarm()
        admissionCountdownView = null
        main.removeCallbacks(admissionRetry)
        main.removeCallbacks(admissionCountdownTick)
    }

    private fun armAdmissionAutoRetry(delayMs: Long, title: String) {
        main.postDelayed(admissionRetry, delayMs)
        applyAdmissionPaint(admissionCountdown.arm(delayMs))
        Log.i(TAG, "admission auto-retry armed in ${delayMs}ms ($title)")
    }

    /**
     * Put back a deadline the shared installer disarmed, at the instant it already held.
     *
     * The delay is derived here rather than passed in, from the same uptime clock the countdown owner
     * and the Handler both use, so however long the redraw took is subtracted rather than added. A
     * deadline that expired mid-redraw posts at zero and fires immediately, which is what the panel
     * would have done had nothing redrawn it.
     */
    private fun resumeAdmissionAutoRetry(deadlineMs: Long, title: String) {
        val delayMs = (deadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        main.postDelayed(admissionRetry, delayMs)
        applyAdmissionPaint(admissionCountdown.rearmAt(deadlineMs))
        Log.i(TAG, "admission auto-retry resumed with ${delayMs}ms left ($title)")
    }

    /** The owner decides whether to repaint and whether to run again; this only carries out the verdict.
     *  A detached view is treated as not visible, so a replaced screen cannot keep a stale row alive. */
    private fun applyAdmissionPaint(paint: AdmissionCountdownOwner.Paint) {
        main.removeCallbacks(admissionCountdownTick)
        val view = admissionCountdownView?.takeIf { it.isAttachedToWindow } ?: return
        paint.remainingMs?.let { remaining ->
            val totalSec = (remaining.coerceAtLeast(0L) + 999L) / 1_000L
            val minutes = totalSec / 60L
            val seconds = totalSec % 60L
            view.text = if (minutes > 0L) {
                getString(R.string.retrying_minutes_seconds, minutes, seconds)
            } else {
                getString(R.string.retrying_seconds, seconds)
            }
        } ?: paint.text?.let { view.text = it }
        paint.scheduleNextTickMs?.let { main.postDelayed(admissionCountdownTick, it) }
    }

    private fun onAdmissionCountdownTick() {
        if (destroyed) return
        applyAdmissionPaint(admissionCountdown.onTick())
    }

    /** Top-visibility, not merely resumed: a translucent Overview can take top-resumed status without
     *  calling onPause, and repainting behind it is invisible work. The retry stays armed throughout. */
    private fun onAdmissionVisibilityChanged(visible: Boolean) {
        if (destroyed) return
        applyAdmissionPaint(admissionCountdown.onVisibilityChanged(visible))
        // Coming forward is what makes a microphone foreground service startable again on Android 14,
        // so a claim the platform refused while the panel was in the background is retried here.
        if (visible) PaneldService.notifyActivityForeground()
    }

    /** Resolve before WebView creation; entity learning may be disabled or may never have scanned. */
    private fun resolveHomeDashboardAndLoad(config: Config) {
        val owner = homeDashboardOwner(config)
        val owned = homeDashboardResolution?.takeIf { it.owner == owner }
        if (owned != null && owned.confirmed) {
            val path = owned.resolution.path
            if (path == null) {
                showBlockedAdmissionScreen(
                    getString(R.string.no_dashboard_title),
                    getString(R.string.no_dashboard_detail),
                    AdmissionOutcome.NO_LEGAL_DASHBOARD,
                )
            } else {
                buildCompatibleAndLoad(config, path)
            }
            return
        }
        // Launch accelerator, no-renderer branch. This is the ordinary cold-start route — a fresh
        // process (and a genuine activity recreation, which brings a new instance with a null `web`)
        // arrives here, not at the still-held-renderer branch below. It also carries every rebuild
        // that tore the renderer down first, which is why it comes BEFORE the in-flight-job early
        // return: a renderer crash while the authenticated resolution is running would otherwise
        // leave the panel blank until that resolution finishes or fails, defeating the immediate
        // cached fallback at the one moment it is most needed.
        if (web == null) {
            (owned?.resolution?.path ?: config.cachedHomeDashboardLaunchPath())?.let { path ->
                if (owned == null) {
                    homeDashboardResolution = OwnedHomeDashboardResolution(
                        owner,
                        EntityLearningProtocol.HomeDashboardResolution(
                            path, EntityLearningProtocol.HomeDashboardSource.CACHED,
                        ),
                        confirmed = false,
                    )
                }
                Log.i(
                    TAG,
                    "home dashboard cache-accelerated launch path=$path renderer=none " +
                        "(cold start or rebuild) — the authenticated resolution refreshes behind it",
                )
                buildCompatibleAndLoad(config, path)
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
            }
        }
        if (homeDashboardCheckingOwner == owner && homeDashboardJob?.isActive == true) return
        homeDashboardJob?.cancel()
        val ticket = homeDashboardAttempts.start(owner)
        homeDashboardCheckingOwner = owner
        // Launch accelerator, still-held-renderer branch: the last path a live resolution produced for
        // this exact owner (endpoint + credential + configured home_dashboard) navigates immediately,
        // and the authenticated resolution below re-runs behind the rendering page. Its answer always
        // wins — the same path confirms the page, a different one corrects it, a confirmed empty list
        // clears the cache.
        //
        // Reachable only when this activity instance still holds a renderer from an earlier load (an
        // in-process relaunch or admission retry, where no teardown intervened). The branch above has
        // already accelerated every launch that started with no renderer, and its provisional
        // resolution makes `provisionalPath` non-null, so the two sites are mutually exclusive.
        var provisionalPath = homeDashboardResolution?.takeIf { it.owner == owner && !it.confirmed }
            ?.resolution?.path
        if (provisionalPath == null && owned == null) {
            config.cachedHomeDashboardLaunchPath()?.let { cached ->
                provisionalPath = cached
                homeDashboardResolution = OwnedHomeDashboardResolution(
                    owner,
                    EntityLearningProtocol.HomeDashboardResolution(
                        cached, EntityLearningProtocol.HomeDashboardSource.CACHED,
                    ),
                    confirmed = false,
                )
                Log.i(
                    TAG,
                    "home dashboard cache-accelerated launch path=$cached renderer=held " +
                        "(relaunch over the renderer this activity still holds) — " +
                        "the authenticated resolution refreshes behind it",
                )
                buildCompatibleAndLoad(config, cached)
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
            }
        }
        if (provisionalPath == null) {
            showAdmissionProgressScreen(
                getString(R.string.selecting_ha_dashboard),
                getString(R.string.selecting_ha_dashboard_detail),
            )
        }
        homeDashboardJob = activityScope.launch {
            val resolution = withContext(Dispatchers.IO) {
                EntityLearningRuntime.resolveHomeDashboard(
                    owner.configuredPath,
                    stillCurrent = {
                        !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
                            homeDashboardAttempts.owns(ticket, homeDashboardOwner(Config(this@DashboardActivity)))
                    },
                    // Refreshing behind a rendered provisional page PROMISES a live answer, so the
                    // process-local authority must not answer for it: a warm relaunch would
                    // otherwise miss an account-default change, a removed dashboard or revoked
                    // access and confirm the cached path against a stale in-process result.
                    forceLive = provisionalPath != null,
                )
            }
            val currentConfig = Config(this@DashboardActivity)
            val currentOwner = homeDashboardOwner(currentConfig)
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) ||
                !homeDashboardAttempts.owns(ticket, currentOwner)
            ) return@launch
            homeDashboardCheckingOwner = null
            val shownPath = homeDashboardResolution
                ?.takeIf { it.owner == currentOwner && !it.confirmed }
                ?.resolution?.path
            if (resolution == null) {
                // A transient failure neither erases the persisted cache nor tears down the page it
                // admitted: with the provisional dashboard rendering, retry quietly behind it.
                if (shownPath == null || web == null) {
                    showBlockedAdmissionScreen(
                        getString(R.string.dashboard_list_unavailable),
                        getString(R.string.dashboard_list_unavailable_detail),
                        AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
                    )
                } else {
                    cancelAdmissionAutoRetry()
                    admissionRetryPolicy.nextDelayMs(
                        admissionRetryClass(AdmissionOutcome.DASHBOARD_LIST_UNREADABLE),
                    )?.let { armAdmissionAutoRetry(it, getString(R.string.dashboard_list_unavailable)) }
                }
                return@launch
            }
            admissionRetryPolicy.reset()
            homeDashboardResolution = OwnedHomeDashboardResolution(currentOwner, resolution)
            val launchOwner = HomeDashboardLaunchCache.ownerFingerprint(
                currentOwner.authOwner, currentOwner.configuredPath,
            )
            if (resolution.path == null) {
                // Confirmed by a COMPLETED list read — distinct from the transient branch above: the
                // account has no legal dashboards, so replaying the cache next launch would navigate
                // somewhere the list just proved unreachable. The cache goes with the screen.
                if (!currentConfig.clearHomeDashboardLaunchPathIfOwned(launchOwner)) {
                    // The durable delete did not commit, so the stale row is still there for the next
                    // launch (or a Retry) to replay. Reporting the settled no-dashboard state here
                    // would claim a convergence that did not happen; keep the retry armed instead so
                    // the invalidation is attempted again.
                    Log.e(TAG, "home dashboard cache invalidation did not commit — retrying before settling")
                    showBlockedAdmissionScreen(
                        getString(R.string.no_dashboard_title),
                        getString(R.string.no_dashboard_cache_clear_detail),
                        AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
                    )
                    return@launch
                }
                showBlockedAdmissionScreen(
                    getString(R.string.no_dashboard_title),
                    getString(R.string.no_dashboard_detail),
                    AdmissionOutcome.NO_LEGAL_DASHBOARD,
                )
                return@launch
            }
            currentConfig.setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)
            Log.i(TAG, "home dashboard resolved source=${resolution.source} path=${resolution.path}")
            val provisionalEpoch = provisionalHomeDashboardEpoch
            provisionalHomeDashboardEpoch = null
            when {
                shownPath == null || web == null -> buildCompatibleAndLoad(currentConfig, resolution.path)
                HomeDashboardLaunchCache.refreshOutcome(shownPath, resolution) ==
                    HomeDashboardLaunchCache.RefreshOutcome.CORRECTED -> {
                    // A navigation chosen AFTER the provisional page (MQTT navigate, idle return,
                    // pull-to-refresh target, a relaunch load) outranks the correction: the resolved
                    // value is already stored for every later navigation, but the page the user is
                    // looking at is not yanked back.
                    if (dashboardNavigationEpoch != provisionalEpoch) {
                        Log.i(
                            TAG,
                            "home dashboard corrected $shownPath -> ${resolution.path} in store only — newer navigation wins on screen",
                        )
                    } else {
                        Log.i(TAG, "home dashboard corrected $shownPath -> ${resolution.path} (cached path superseded)")
                        navigateAfterHomeDashboardCorrection(resolution.path)
                    }
                }
                else -> Unit // CONFIRMED — the rendering page is already the resolved dashboard
            }
        }
    }

    /** Move an already rendering page onto the corrected home dashboard: instantly over the external
     *  bus when the frontend is live, otherwise a fresh load of the corrected target — a mid-load or
     *  interstitial page would otherwise finish on the superseded dashboard and stay there.
     *
     *  A bus navigate has no page-load events, so its completion is PROVEN, not assumed: one bounded
     *  verification reads the page's location afterwards and, if the correction did not land (and no
     *  newer navigation arrived meanwhile), escalates to the full corrected load, whose handshake
     *  watchdog then owns convergence. Without this a silently dropped bus command would leave the
     *  screen on the superseded dashboard while store and resolution both claim the corrected one. */
    private fun navigateAfterHomeDashboardCorrection(path: String) {
        val w = web ?: return
        if (frontendConnected && !authLatched && !interstitialShown) {
            sendBusNavigate(path)
            val issuedEpoch = dashboardNavigationEpoch
            val generation = rendererGeneration
            main.postDelayed(
                {
                    if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return@postDelayed
                    if (!rendererCurrent(generation, web) || dashboardNavigationEpoch != issuedEpoch) return@postDelayed
                    val currentPath = runCatching { android.net.Uri.parse(web?.url).path }.getOrNull()
                    if (!HomeDashboardLaunchCache.correctionConverged(currentPath, path)) {
                        Log.w(TAG, "home dashboard correction unverified after bus navigate — loading the corrected target")
                        loadCorrectedHomeDashboard()
                    }
                },
                CORRECTION_VERIFY_MS,
            )
            return
        }
        loadCorrectedHomeDashboard()
    }

    /** Full-load half of the correction: the same rotate + load + watchdog route every other fresh
     *  navigation uses, reading the corrected target through [currentUrl]. */
    private fun loadCorrectedHomeDashboard() {
        val w = web ?: return
        val config = Config(this)
        // Unresolved here means the owner moved after the correction; its rebuild loads the new home.
        val home = resolvedHomeDashboard(config) ?: return
        if (!rotateBusDocument(w, config, rendererGeneration)) return
        interstitialShown = false
        noteDeliberateDashboardNavigation()
        val target = currentUrl(config, home)
        noteAppNavigationTarget(android.net.Uri.parse(target).path.orEmpty())
        expectPageStart(target)
        w.loadUrl(target)
        onLoadStarted()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildCompatibleAndLoad(config: Config, homePath: String) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (compatibilityReadyUrl != config.haUrl.trim().trimEnd('/')) return
        if (holdForEntityBootstrap(config) || entityFilterNativeHold != null) {
            showWaitingForEntityBootstrap()
            return
        }
        // Admission succeeded: a surviving blocked-screen timer firing under the live dashboard would
        // tear it down to re-probe, so it dies here, and the back-off starts fresh next time.
        cancelAdmissionAutoRetry()
        admissionRetryPolicy.reset()
        main.removeCallbacks(entityBootstrapCheck)
        entityBootstrapBlockedCount = -1
        val generation = rendererGate.open()
        rendererGeneration = generation
        // The page carries a live HA session, so only expose the WebView's DevTools socket when network
        // adb is deliberately on (a debug posture) — not by default. The CDP relay (also off by default)
        // is the LAN publisher on top of this.
        WebView.setWebContentsDebuggingEnabled(
            shouldEnableWebViewDebugging(config.networkAdbEnabled, config.hardenedSecurityEnabled),
        )
        // A rebuild (renderer crash) discards the old root that held any fullscreen (onShowCustomView)
        // view. Clear the stale references so the crash didn't leave customView non-null — otherwise every
        // future onShowCustomView would be rejected and fullscreen video would be permanently broken.
        customView = null
        customViewCallback = null
        val session = externalBus.beginDocument(generation, config.dashboardNativeKiosk)
        externalBusSession = session
        v2Handshake.begin(session)
        authQueue.clear()
        v2BridgeDocument = V2BridgeDocument(config, generation, session, entityFilterLease)
        val w = try {
            createWebView(config, generation)
        } catch (e: Throwable) {
            externalBus.invalidate()
            externalBusSession = null
            v2BridgeDocument = null
            v2ListenerView = null
            if (e is EntityFilterInterceptorUnavailable) {
                showWaitingForEntityBootstrap()
                return
            }
            if (e is ExternalV2BridgeUnavailable) {
                showBlockedAdmissionScreen(
                    getString(R.string.home_assistant_could_not_open),
                    getString(R.string.home_assistant_could_not_open_detail),
                    AdmissionOutcome.BRIDGE_ATTACH_FAILED,
                )
                return
            }
            // A missing / updating / broken system WebView (exactly the population WebView-auto-heal
            // targets) throws here. As a HOME activity we must not crash — Android would relaunch us
            // into a crash-loop — so fall back to the admin launcher. This consumes the rebuild budget
            // too: the kiosk/watchdog loops relaunch us, createWebView throws again, and without the
            // budget that cycle would churn forever; with it the renderer latches after a burst and
            // retries once per cooldown (plenty for a WebView update to finish installing).
            Log.e(TAG, "system WebView unavailable — falling back to admin launcher", e)
            BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())
            fallbackToLauncher()
            return
        }
        web = w
        committedPageUrl = null
        // Wrap in a pull-to-refresh layout: a drag that starts at the very top edge of the screen and
        // pulls down does a light reload of the current page (no app relaunch). The gesture is gated on
        // its ORIGIN (see EdgePullRefreshLayout) — a downward drag that begins inside the dashboard
        // content never triggers it, so scrolling views and adjusting cards behave normally (#29).
        // The spinner is cleared when the page finishes (or errors) — see the WebViewClient.
        val refresh = EdgePullRefreshLayout(this).apply {
            addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { lightRefresh() }
        }
        swipe = refresh
        // A dark root behind the (transparent) WebView so a reload never flashes white — very visible on
        // a wall panel at night. Also hosts the fullscreen-video view from onShowCustomView. The root is a
        // BottomSwipeFrame so a bottom-edge swipe-up reveals the soft navbar in-process (see the class) —
        // replacing the service's overlay strip that made the dashboard's bottom band tap-dead.
        val container = BottomSwipeFrame(
            this,
            enabled = { BuiltinDashboard.navbarSwipeEnabled },
            onSwipeUp = { BuiltinDashboard.requestNavbarReveal() },
        ).apply {
            setBackgroundColor(BG_DARK)
            addView(refresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root = container
        setContentView(container)
        statusRerender = null
        noteDeliberateDashboardNavigation()
        // Attached to the same frame the fullscreen-video view uses, so the dashboard keeps rendering
        // underneath. Seeded from the CURRENT state because a renderer rebuilt mid-outage must not come
        // up looking calm.
        lifecycleBar = HaLifecycleBar.attach(this, container)
        networkChip = HaNetworkChip.attach(this, container)
        redrawLifecycleBar()
        val target = currentUrl(config, homePath)
        noteAppNavigationTarget(android.net.Uri.parse(target).path.orEmpty())
        expectPageStart(target)
        w.loadUrl(target)
        onLoadStarted()
        // buildAndLoad is reached from service-triggered singleTask intents and renderer recovery as
        // well as initial startup. Own dark settling here so no caller can leave a replacement WebView
        // running indefinitely behind a sleeping display while it waits for a connection callback.
        if (deferReadyEntityBootstrapUntilWake(screenAwake)) scheduleDarkSettle()
    }

    /** Native hold screen used while the learner derives its first minimal subscription. No WebView is
     * created here, making it impossible for an activity/watchdog/HOME race to open the full HA stream. */
    /**
     * Answer the filter question from the panel itself, declining the filter.
     *
     * Records the same answer the wizard's decline button records, so the two surfaces cannot disagree about
     * whether the question was asked. Deliberately does NOT enable filtering: someone pressing skip wants a
     * dashboard now, and silently turning on a feature they were offered and passed over would be worse than
     * the slow load they accepted.
     *
     * Also answers the dashboard question as "follow the account's default" (home_dashboard stays blank):
     * skip means "stop asking me things and show a dashboard", so leaving the OTHER open question armed
     * would keep the Set up tab and its banner nagging a panel whose user just declined the wizard.
     */
    private fun skipEntityFilterQuestion() {
        val config = Config(this)
        config.setupEntityFilterAnswered = true
        config.setupHomeDashboardChosen = true
        main.removeCallbacks(entityFilterAnswerCheck)
        buildAndLoad(config)
    }

    /**
     * The pre-render screen shown while the wizard's entity-filter question is open.
     *
     * Says what it is waiting for and that the wait is not the panel's fault, because someone walking up to a
     * panel mid-setup must be able to tell a deliberate pause from a hang. Static by design: no WebView, no
     * network, nothing to churn — it exists only so the first dashboard the user sees is the filtered one.
     */
    private fun showWaitingForEntityFilterAnswer() {
        cancelAdmissionAutoRetry()
        main.removeCallbacks(entityBootstrapCheck)
        main.removeCallbacks(entityFilterAnswerCheck)
        teardownWeb()
        val config = Config(this)
        // The exact page the question is on, so a passer-by does not have to hunt for it. Not a QR: this
        // screen appears mid-setup when the browser is already open somewhere, so the address is what is
        // actually useful, and generating a bitmap here would cost a synchronous encode for nothing.
        val setupUrl = LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort, "/setup")
        val surface = statusSurface(config)
        surface.setBody(
            surface.heading(getString(R.string.finish_questions_browser)),
            surface.detail(setupUrl, accent = true),
            // Escape hatch. The hold exists to protect the first impression, not to trap anyone: someone at
            // the panel with no browser to hand must be able to get a dashboard. Says what it costs, because
            // skipping is the unfiltered load this screen exists to avoid.
            surface.action(getString(R.string.skip_load_dashboard)) { skipEntityFilterQuestion() },
            surface.caption(getString(R.string.skip_entity_filter_detail)),
        )
        showStatusSurface(surface) { showWaitingForEntityFilterAnswer() }
        main.postDelayed(entityFilterAnswerCheck, ENTITY_FILTER_ANSWER_CHECK_MS)
    }

    /** Where the decision on the hold screen can also be answered from another device. */
    private fun entitiesPageAddress(): String? {
        val ip = io.github.maxlyth.hapaneld.metrics.PanelMetrics.shared.ipAddress()
        return ip?.let { "http://$it:8888/entities" }
    }

    private fun localizedBootstrapMilestone(): String {
        val milestone = EntityLearningRuntime.bootstrapMilestoneState() ?: return ""
        return when (milestone.stage) {
            1 -> milestone.count?.let { getString(R.string.entity_scan_step_reading_count, it) }
                ?: getString(R.string.entity_scan_step_reading)
            else -> getString(R.string.entity_scan_step_building, milestone.count ?: 0)
        }
    }

    private fun localizedEntityFilterHoldDetail(hold: EntityFilterNativeHold): String = when (hold.error) {
        "invalid_configuration" -> getString(R.string.entity_filter_invalid_config)
        "document_start_unsupported" -> getString(R.string.entity_filter_webview_too_old)
        "document_start_install" -> getString(R.string.entity_filter_setup_failed)
        else -> hold.detail
    }

    private fun showWaitingForEntityBootstrap() {
        cancelAdmissionAutoRetry()
        main.removeCallbacks(entityBootstrapCheck)
        teardownWeb()
        val filterHold = entityFilterNativeHold
        val blockingIssues = EntityLearningRuntime.blockingIssueCount()
        val canIgnoreBlockingIssues = blockingIssues > 0 && EntityLearningRuntime.canIgnoreBlockingIssues()
        // Past the watchdog's give-up deadline a formless hold PRESENTS as a synchronization problem so
        // the retry/disable buttons appear — the happy spinner must never be terminal.
        val bootstrapProblem = EntityLearningRuntime.bootstrapProblem()
            ?: if (entityBootstrapWatchdog.gaveUp) EntityBootstrapProblem.SYNCHRONIZATION else null
        entityBootstrapBlockedCount = blockingIssues
        entityBootstrapProblem = bootstrapProblem
        val surface = statusSurface()
        val rows = mutableListOf<View>()
        run {
            if (blockingIssues == 0 && filterHold == null && bootstrapProblem == null) {
                // This screen says, before the burst starts, that the dashboard may reload while the panel
                // learns; the reloads that follow it stay quiet rather than each announcing a restart.
                BuiltinDashboard.restartAnnouncements.learningNoticeShown(SystemClock.elapsedRealtime())
                // Deterministic milestones, not a spinner: nobody trusts the circle (hardware review),
                // and this text updates with the live scan count on every bootstrap poll tick.
                entityBootstrapMilestoneView = surface.detail(
                    localizedBootstrapMilestone(),
                    accent = true,
                )
                rows += entityBootstrapMilestoneView!!
            }
            rows += surface.heading(
                if (filterHold != null) {
                    getString(R.string.optimized_subscription_unavailable)
                } else if (blockingIssues > 0) {
                    getString(R.string.entity_filter_attention)
                } else if (bootstrapProblem == EntityBootstrapProblem.AUTHENTICATION) {
                    getString(R.string.ha_refused_entity_scan)
                } else if (bootstrapProblem != null) {
                    getString(R.string.dashboard_scan_failed)
                } else {
                    getString(R.string.preparing_optimized_subscription)
                },
            )
            val bootstrapHint = surface.detail(
                if (filterHold != null) {
                    getString(R.string.entity_filter_hold_detail, localizedEntityFilterHoldDetail(filterHold))
                } else if (blockingIssues > 0) {
                    if (canIgnoreBlockingIssues) {
                        entitiesPageAddress()?.let { address ->
                            getString(R.string.entity_filter_attention_remote, blockingIssues, address)
                        } ?: getString(R.string.entity_filter_attention_detail, blockingIssues)
                    } else {
                        getString(R.string.too_many_flagged_entities)
                    }
                } else if (bootstrapProblem == EntityBootstrapProblem.AUTHENTICATION) {
                    getString(R.string.entity_scan_auth_rejected)
                } else if (bootstrapProblem != null) {
                    getString(R.string.entity_scan_failed_detail)
                } else {
                    getString(R.string.entity_scan_preparing_detail)
                },
            )
            rows += bootstrapHint
            if (blockingIssues == 0 && filterHold == null && bootstrapProblem == null) {
                // Its own row, so the honesty rung below that replaces the hint cannot take it away.
                rows += surface.detail(getString(R.string.entity_learning_reload_notice))
                // The happy wait needs an honesty rung of its own: watched live, a stuck first scan held
                // this screen for many minutes with no acknowledgement, indistinguishable from a hang.
                // The sync rerun-latch fixes the known cause; this line is the promise kept if another
                // slow path appears. Text only — the settings escape below is already on screen.
                main.postDelayed({
                    if (!destroyed && bootstrapHint.isAttachedToWindow) {
                        bootstrapHint.setText(R.string.entity_scan_taking_longer)
                    }
                }, BOOTSTRAP_HOLD_HONESTY_MS)
            }
            if (filterHold != null) rows += surface.action(getString(R.string.try_again)) {
                retryEntityFilter(Config(this@DashboardActivity))
            }
            if (filterHold == null && bootstrapProblem != null) rows += surface.action(getString(R.string.try_again)) {
                // A user-driven retry restores hope: the watchdog clock restarts and the screen
                // returns to the progress presentation until the new deadline.
                entityBootstrapWatchdog.restart(SystemClock.elapsedRealtime())
                if (EntityLearningRuntime.retryBootstrap()) showWaitingForEntityBootstrap()
            }
            if (filterHold == null && blockingIssues > 0) {
                if (canIgnoreBlockingIssues) {
                    rows += surface.action(
                        getString(R.string.ignore_flagged_entities),
                        primary = true,
                        fullWidth = true,
                    ) { button ->
                        button.isEnabled = false
                        button.setText(R.string.preparing_dashboard)
                        if (!EntityLearningRuntime.ignoreBlockingIssues()) {
                            button.isEnabled = true
                            button.setText(R.string.ignore_flagged_entities)
                        }
                    }
                }
                rows += surface.action(getString(R.string.disable_entity_filter), fullWidth = true) { button ->
                    button.isEnabled = false
                    if (!EntityLearningRuntime.disableAutomaticFilter()) button.isEnabled = true
                }
            }
            rows += surface.action(
                if (filterHold != null || bootstrapProblem == EntityBootstrapProblem.AUTHENTICATION) {
                    getString(R.string.configure)
                } else {
                    getString(R.string.open_entity_settings)
                },
                fullWidth = blockingIssues > 0,
            ) {
                // Configure for both repairs that live there — the sign-in, and the entity-filter switch
                // (`dashboard_entity_learning`), which an Entities reset preserves. Entities only where
                // the task really is reviewing flagged entities.
                val path = if (filterHold != null || bootstrapProblem == EntityBootstrapProblem.AUTHENTICATION) {
                    "/configure"
                } else {
                    "/entities"
                }
                startActivity(Intent(this@DashboardActivity, ConfigActivity::class.java).putExtra("path", path))
            }
        }
        surface.setBody(*rows.toTypedArray())
        showStatusSurface(surface) { showWaitingForEntityBootstrap() }
        if (filterHold != null) scheduleEntityFilterRetry()
        else main.postDelayed(entityBootstrapCheck, ENTITY_BOOTSTRAP_CHECK_MS)
        Log.i(TAG, if (filterHold != null) {
            "automatic entity filter held before WebView creation (${filterHold.error})"
        } else if (blockingIssues > 0) {
            "automatic entity filter awaiting a choice for $blockingIssues safety issue(s)"
        } else {
            "automatic entity set not ready — holding renderer before WebView creation"
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        config: Config,
        generation: Long,
    ): WebView = WebView(this).apply {
        val documentStartOrigins = dashboardDocumentStartOrigins(config.haUrl)
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
        setBackgroundColor(BG_DARK) // no white flash before first paint
        // Overscroll stretch/glow off by default (see applyOverscroll) — set before first layout.
        overScrollMode = if (config.dashboardOverscroll) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_NEVER
        // Page zoom to match the HA Companion's default sizing (it scales by device density); pinch
        // stays off (no builtInZoomControls) — the zoom is a deliberate per-panel value (see applyZoom).
        setInitialScale((resources.displayMetrics.density * config.dashboardZoom).toInt())
        applyForceDark(this)
        // The colour-scheme policy runs on every panel and every Android version, and the signature is
        // recorded HERE rather than at the call site because this is the WebView it is baked into:
        // onNewIntent compares that signature against config to decide whether to rebuild.
        val forcedThemeDark = DashboardTheme.forcedDark(config.dashboardTheme)
        dashboardThemeSignature = config.dashboardTheme
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
        // Intercept only the primary HA socket's outbound subscribe_entities command. The socket itself
        // remains Chromium-native, preserving its TLS, compression and external-app lifecycle signals.
        val filterLease = entityFilterLease
        if (filterLease != null && EntityFilterTelemetry.isActive(filterLease)) {
            try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    EntityFilterProtocol.documentStartScript(
                        config.haUrl,
                        config.dashboardEntityFilterIds,
                        documentStartOrigins,
                    ),
                    documentStartOrigins,
                )
                entityFilterRetryPolicy.reset()
            } catch (error: Throwable) {
                if (fallbackFromEntityFilterInterceptor(error, config.dashboardEntityLearningEnabled)) {
                    runCatching { destroy() }
                    throw EntityFilterInterceptorUnavailable(error)
                }
            }
        }
        // The enabled measurement arm observes the exact HA entity socket in both filter-on and
        // filter-off runs. The paired -PfeatureCosts=false arm must install no observer at all, otherwise
        // its JSON parsing, PerformanceObservers and five-second bridge contaminate the baseline.
        if (shouldInstallDashboardTrafficObserver(
                featureCostsEnabled = BuildConfig.FEATURE_COSTS_ENABLED,
                filterLeasePresent = filterLease != null,
            )
        ) runCatching {
            if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    EntityFilterProtocol.trafficObserverDocumentStartScript(config.haUrl, documentStartOrigins),
                    documentStartOrigins,
                )
                EntityFilterTelemetry.trafficObserverInstalled(requireNotNull(filterLease))
            }
        }.onFailure { Log.w(TAG, "entity-filter traffic observer unavailable", it) }
        if (config.dashboardEntityLearningEnabled) runCatching {
            if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    EntityLearningProtocol.documentStartScript(
                        config.haUrl,
                        documentStartOrigins,
                        BuildConfig.FEATURE_COSTS_ENABLED,
                    ),
                    documentStartOrigins,
                )
            }
        }.onFailure { Log.w(TAG, "entity-learning access observer unavailable", it) }
        // The HA frontend relies on cookies (incl. third-party for some integrations).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        try {
            installV2Listeners(this, config)
        } catch (error: Throwable) {
            runCatching { destroy() }
            throw ExternalV2BridgeUnavailable(error)
        }
        webChromeClient = dashboardChromeClient(generation)
        webViewClient = object : WebViewClient() {
            // A dead renderer process must not take the app down with it: rebuild the WebView in place
            // (else Android kills the whole process + the panel's HTTP/MQTT service), but within a budget
            // so a reliably-crashing page falls back to the admin launcher instead of spinning.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer process gone (crash=${detail.didCrash()})")
                val owned = rendererCurrent(generation, view)
                // An OOM-killed renderer can leave a corrupted Chromium code-cache behind, which can
                // crash the REBUILT renderer too — clear it (best-effort; the view is defunct and may
                // throw) so the crash budget isn't burned on a persistent on-disk cause.
                runCatching { view.clearCache(true) }
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (owned) {
                    rendererGate.invalidate()
                    externalBus.invalidate()
                    externalBusSession = null
                    v2BridgeDocument = null
                    v2ListenerView = null
                    detachLifecycleBar()
                    authQueue.clear()
                    v2Handshake.reset()
                    clearBusTimeouts()
                    web = null
                    committedPageUrl = null
                    main.removeCallbacks(watchdog)
                    main.removeCallbacks(darkSettle)
                    runCatching { customViewCallback?.onCustomViewHidden() }
                    customView = null
                    customViewCallback = null
                    runOnUiThread {
                        if (allowRebuild()) {
                            BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary crash rebuild
                            buildAndLoad(Config(this@DashboardActivity))
                        } else { Log.e(TAG, "renderer crash-looping — falling back to admin launcher"); fallbackToLauncher() }
                    }
                }
                return true
            }

            // Keep navigation on the dashboard authority (the panel has no browser). A same-host HTTP
            // dashboard may upgrade to HTTPS behind a proxy/HSTS, but an HTTPS dashboard may never
            // downgrade and hand its external-auth bridge to cleartext content.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!rendererCurrent(generation, view)) return true
                val allowed = dashboardNavigationAllowed(config.haUrl, request.url.toString())
                if (allowed && request.isForMainFrame) {
                    // Ownership transfers when the navigation BEGINS. doUpdateVisitedHistory alone is
                    // too late: a correction completing between these two callbacks would overwrite a
                    // main-frame navigation the user had already started.
                    if (observedDashboardNavigationIsForeign(request.url.toString())) {
                        noteDeliberateDashboardNavigation()
                    }
                    if (!rotateBusDocument(view, config, generation)) return true
                    expectPageStart(request.url.toString())
                    onLoadStarted()
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!rendererCurrent(generation, view)) return
                committedPageUrl = url
                val expected = expectedPageStartUrl.also { expectedPageStartUrl = null }
                if (!dashboardNavigationAllowed(config.haUrl, url)) {
                    // Native recovery/auth-latch documents are intentionally bridge-free. Their
                    // loadData navigation also reaches this callback, so never let the redirect
                    // backstop reattach either V2 object to an opaque/local document.
                    suspendBusDocument(view)
                    return
                }
                if (expected == url) return
                // Redirects and renderer-initiated reloads can bypass shouldOverrideUrlLoading. They
                // are real navigations that were previously only claimed at doUpdateVisitedHistory,
                // leaving a window in which a delayed correction could still win; claim here too.
                // `expected == url` above already returned for echoes of app-issued loads.
                if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()
                // Rotate only the typed document context here; both listeners were attached before the
                // first load and remain installed throughout this navigation.
                runCatching { beginBusDocument(view, config, generation) }
                    .onSuccess { onLoadStarted() }
                    .onFailure {
                        Log.e(TAG, "secure V2 document rotation failed", it)
                        showBlockedAdmissionScreen(
                            getString(R.string.home_assistant_stopped_loading),
                            getString(R.string.home_assistant_stopped_loading_detail),
                            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
                        )
                    }
            }

            // Real navigation inside Home Assistant's own frontend — a tapped link, a back gesture,
            // or an SPA history change that never reaches onPageStarted. The launch-cache correction
            // must observe THIS, not only the app's own dispatch sites: a user who walks away from
            // the provisional dashboard while the background answer is still pending would otherwise
            // be yanked back by the delayed correction.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!rendererCurrent(generation, view)) return
                if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()
            }

            // Stop the pull-to-refresh spinner once the (main-frame) load settles, success or error, so
            // it never spins forever on a hung reload.
            override fun onPageFinished(view: WebView, url: String) {
                if (!rendererCurrent(generation, view)) return
                externalBusSession?.takeIf { bridgeCurrent(generation, it) }?.let(v2Handshake::finish)
                swipe?.isRefreshing = false
                // Re-assert the page zoom AFTER load — HA's frontend ships its own <meta viewport
                // initial-scale=1>, which overrides a scale set before load, so a pre-load setInitialScale
                // silently reverts to default (dashboard looks compact). HACA does exactly this in its
                // own onPageFinished. Keeps our sizing matching the Companion app's.
                applyZoom()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                // Replace Android's un-styleable gray net::ERR_* page with the branded reconnecting
                // screen; the handshake watchdog (already armed by this load) keeps retrying behind it.
                val detail = "${error.description}".replace("<", "&lt;")
                runOnUiThread { showReconnecting(detail) }
            }

            // HTTP errors are consulted ONLY for the main frame: a sub-resource 401/404 (a HACS module,
            // a picture card) is that card's problem, not the page's — reacting to it (HACA's early
            // mistake) turns one broken card into a broken dashboard. A main-frame error just logs;
            // recovery is the handshake watchdog's job (no `connected` will arrive → reload w/ backoff).
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                Log.w(TAG, "main-frame HTTP ${errorResponse.statusCode}")
            }
        }
    }

    /**
     * Without a WebChromeClient, three dashboard interactions fail SILENTLY: JS `confirm()` (the frontend
     * uses it for destructive actions — the UI hangs on an unanswered result), fullscreen video, and
     * getUserMedia permission requests. This provides the minimum: native dialogs for confirm/alert,
     * fullscreen show/hide, and a permission handler that grants only resources whose Android permission
     * we actually hold — mic is out of scope (the manifest declares no RECORD_AUDIO today, so this stays
     * denied) and the camera is refused outright, because the camera trial's session owner is the only
     * thing allowed to open the device and a page's `getUserMedia()` is not one of its subscribers —
     * else denies explicitly so the card shows its own error instead of hanging.
     */
    private fun dashboardChromeClient(generation: Long) = object : WebChromeClient() {
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            // Showing a dialog on a finishing/destroyed activity throws BadTokenException and takes the
            // whole process down. If we can't show it, cancel the JS call so the frontend isn't left hung.
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.cancel(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.confirm(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!rendererCurrent(generation)) { callback.onCustomViewHidden(); return }
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            root?.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            if (!rendererCurrent(generation)) return
            customView?.let { root?.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!rendererCurrent(generation)) { request.deny(); return }
            val granted = webViewCaptureGrants(
                requested = request.resources,
                permissionHeld = { permission ->
                    ContextCompat.checkSelfPermission(this@DashboardActivity, permission) == PackageManager.PERMISSION_GRANTED
                },
                microphoneIdle = MicrophoneAdmission.isIdle,
                webViewCaptureAllowed = MicrophoneAdmission.webViewCaptureAllowed,
            )
            if (granted.isNotEmpty()) request.grant(granted) else request.deny()
        }
    }

    companion object {
        private const val TAG = "ha-paneld/dashboard"
        /** Camera trial: the CAMERA runtime-permission request raised when the camera
         *  setting turns on. Distinct from any other request code — this activity had none before. */
        private const val REQUEST_CAMERA_PERMISSION = 4801
        private const val SIGN_IN_LOAD_RETRIES_MAX = 4
        private const val SIGN_IN_LOAD_RETRY_MS = 4_000L
        private const val ADMISSION_COUNTDOWN_TICK_MS = 1_000L  // repaint cadence of the visible retry countdown
        // Tightened once commit-from-catalog made the happy bootstrap take milliseconds: the net now
        // assumes seconds are normal and anything past twenty is a fault.
        private const val BOOTSTRAP_HOLD_HONESTY_MS = 20_000L
        /** Named once so the button that offers the repair and the button that is handed back
         *  after a refusal cannot drift apart. */
        /** Slow on purpose: the installer publishes a string, not an event, and a download that
         *  runs for minutes gains nothing from being asked about more often than the person
         *  watching would notice. */
        private const val WEB_VIEW_REPAIR_POLL_MS = 2_500L
        private const val BOOTSTRAP_WATCHDOG_RETRY_MS = 30_000L
        private const val BOOTSTRAP_WATCHDOG_PROBLEM_MS = 90_000L
        /** The widening retry never idles longer than this, so a panel converges once HA answers. */
        private const val BOOTSTRAP_WATCHDOG_RETRY_CEILING_MS = 300_000L
        // While the hold waits on a person's decision, the only useful question is whether the dashboard
        // changed. One authenticated read, five minutes after the hold settles, then widening to hourly.
        private const val BOOTSTRAP_WATCHDOG_PROBE_MS = 300_000L
        private const val BOOTSTRAP_WATCHDOG_PROBE_CEILING_MS = 3_600_000L
        private const val INITIAL_HANDSHAKE_MS = 25_000L        // generous: a cold PX30 frontend can need 20s+
        private const val RELOAD_INTERVAL_MS = 6 * 60 * 60 * 1000L   // shed WebView memory at a screen-off, ~6h
        private const val RELOAD_HARD_MS = 26 * 60 * 60 * 1000L      // force a visible reload if never idle-dark
        private const val PERIODIC_CHECK_MS = 30 * 60 * 1000L        // how often the memory-ceiling check runs
        private const val DARK_SETTLE_MS = 30_000L              // let a screen-off reload load before freezing
        private const val BG_DARK = 0xFF121212.toInt()
        private const val LIGHT_REFRESH_SPINNER_MS = 800L       // bus navigate is instant; brief spinner ack
        private const val HARD_REFRESH_WINDOW_MS = 6_000L       // second pull inside this = full hard reload
        private const val CORRECTION_VERIFY_MS = 10_000L        // bus correction must prove itself by then
        private const val IDLE_CHECK_MS = 60_000L               // idle return-to-home tick
        private const val AUTH_INVALID_LATCH = 3                // consecutive auth-invalid events → latch
        private const val REFRESH_REJECT_LATCH = 2              // consecutive definitive refresh rejections → latch
        private const val DEFAULT_NETWORK_WAIT_MS = 60_000L     // calm first-boot progress until learned
        private const val ENTITY_BOOTSTRAP_CHECK_MS = 1_000L    // missed-relaunch backstop; no network work
        // Backstop only: answering normally relaunches the renderer from the server. This catches the case
        // where that relaunch is missed, so the panel still proceeds on its own. Local pref read, no network.
        private const val ENTITY_FILTER_ANSWER_CHECK_MS = 1_500L
        private const val WAKE_MEDIA_SETTLE_MS = 3_000L         // let resumeTimers/onResume reach the page first
        private const val WAKE_MEDIA_SAMPLE_MS = 6_000L         // tolerate slow camera transport reconstruction
        private const val V2_MISSING_RELOAD_LIMIT = 2           // live-compatible HA but no V2 envelope after one retry
        private const val EXTERNAL_APP_V2 = "externalAppV2"
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
private class EdgePullRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
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
 * renderer is foreground — replacing [io.github.maxlyth.hapaneld.control.NavbarController]'s bottom
 * overlay strip, which consumed every touch in its 48dp band and made the dashboard's bottom edge
 * tap-dead (root `input tap` re-injection) or tap-dropping (no root). Qualification is by the gesture's
 * ORIGIN in a bottom edge band, never by content-scroll state — the same principle as
 * [EdgePullRefreshLayout] at the top edge. A gesture that never crosses the upward-travel threshold is
 * NEVER intercepted, so taps and scrolls reach the WebView untouched with zero latency.
 */
private class BottomSwipeFrame(
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

/** Normalize only the route portion; query values and fragments are opaque navigation state. */
internal fun normalizeDashboardTarget(rawTarget: String): String {
    val raw = rawTarget.trim()
    val suffixAt = listOf(raw.indexOf('?'), raw.indexOf('#')).filter { it >= 0 }.minOrNull() ?: raw.length
    return raw.substring(0, suffixAt).trim('/').plus(raw.substring(suffixAt))
}

/**
 * Pure reply-builders for the HA frontend's external-auth / external-bus JS contract. Null = no
 * reply (never evaluate anything for a payload we don't recognise — callback names in particular
 * are validated against the frontend's fixed constants, so an attacker-supplied function name is
 * dropped rather than executed).
 */
object ExternalAuthProtocol {

    private const val MAX_AUTH_PAYLOAD_CHARS = 4 * 1024

    /** Build the dashboard URL: `<haUrl>/<path>?external_auth=1`. [path] is an optional local dashboard
     *  path (e.g. `my-panel/dash` or `/lovelace/0`); leading/trailing route slashes are normalised,
     *  query/fragment state is preserved, and blank means the HA root. `external_auth=1` tells the
     *  frontend to authenticate via our JS bridge. */
    fun dashboardUrl(haUrl: String, path: String): String {
        val base = haUrl.trim().trimEnd('/')
        val p = normalizeDashboardTarget(path)
        val fragmentAt = p.indexOf('#').let { if (it >= 0) it else p.length }
        val resource = p.substring(0, fragmentAt)
        val fragment = p.substring(fragmentAt)
        val separator = when {
            '?' !in resource -> "?"
            resource.endsWith('?') || resource.endsWith('&') -> ""
            else -> "&"
        }
        return "$base/$resource${separator}external_auth=1$fragment"
    }

    /**
     * JS that writes the HA frontend's own per-device theme store (`selectedTheme` in localStorage —
     * exactly what the profile page's Auto/Light/Dark radio writes). This is the lever that actually
     * re-renders HA: the WebView force-dark route is a no-op on HA because WEB_THEME_DARKENING_ONLY
     * only acts on pages whose `color-scheme` meta declares dark support, and HA only declares it
     * AFTER it has already gone dark (verified live on an Android 8.1 panel, 2026-07-10 — the media
     * query never flips, so force-dark alone never darkened HA at all). [onlyIfAbsent] = seed a
     * DEFAULT without stomping a theme the user picked in HA; false = a deliberate dark-mode toggle,
     * which overrides the stored value the way Home Assistant's own radio does.
     *
     * Like that radio, it changes exactly one field. The store holds `ThemeSettings`, so a write that
     * stringified a fresh `{dark:X}` would take the user's named theme and custom colours down with
     * it — invisibly, because the frontend falls back to `default_dark_theme`/`default_theme` when
     * `theme` is absent and the panel goes on rendering. It therefore runs the same read-modify-write
     * the `dashboard_theme` policy uses (see [InjectionScript.readThemeStoreJs]).
     *
     * The seed is guarded on the RAW stored string rather than on the parse, so a panel that already
     * has an entry is left alone even when that entry is unparseable — seeding is for a fresh panel,
     * and rewriting a value ha-paneld cannot read is not seeding, it is stomping.
     */
    fun selectedThemeJs(dark: Boolean, onlyIfAbsent: Boolean): String {
        val key = JSONObject.quote(InjectionScript.SELECTED_THEME_KEY)
        val body = InjectionScript.readThemeStoreJs(key) +
            (if (onlyIfAbsent) "if(r)return;" else "") +
            InjectionScript.RECOVER_THEME_STORE +
            InjectionScript.writeThemeDarkJs(key, dark)
        return "(()=>{${InjectionScript.TOP_FRAME_GUARD}try{$body}catch(e){}})();"
    }

    /**
     * Document-start JavaScript implementing the `dashboard_theme` policy.
     *
     * [forcedDark] null means Follow Home Assistant and produces the HAND-BACK script; true/false
     * produce the FORCE script. Both are self-gating and idempotent, so they can run on every page
     * load, and both must run before Home Assistant's bundle: the frontend captures its
     * `prefers-color-scheme` MediaQueryList at MODULE scope and reads `localStorage.selectedTheme` as
     * its pre-connection bootstrap seed.
     *
     * Forcing is two layers, because neither alone covers the cases this setting exists for:
     *
     *  1. A NARROW `matchMedia` shim. The frontend resolves `themeSettings?.dark === undefined ?
     *     darkPreferred : themeSettings.dark`, so the shim is what decides for every "Auto" user —
     *     including one whose theme lives in Home Assistant's per-user SERVER storage, where the value
     *     mirrored back down over `selectedTheme` after connect would otherwise beat anything written
     *     here. It also decides the FIRST PAINT, before Home Assistant has connected at all. It shims
     *     only `prefers-color-scheme` queries and delegates every other query to the real
     *     implementation — answering all of them would break the frontend's layout breakpoints.
     *  2. A read-modify-write of the `dark` field of `selectedTheme`. This is what beats an EXPLICIT
     *     stored light/dark, which the shim cannot touch because `themeSettings.dark` is then defined.
     *     It is required rather than optional on Android 9- panels, where ha-paneld's own `dark_mode`
     *     seed has already planted an explicit `dark` in that store.
     *
     * What forcing deliberately does NOT do is write Home Assistant's server-side per-user preference.
     * That value is shared with the user's phone and laptop, so a wall panel must never set it. The
     * consequence is a named boundary rather than a hidden failure: an explicit theme chosen inside
     * Home Assistant still wins over the panel's policy.
     */
    fun dashboardThemePolicyJs(forcedDark: Boolean?): String {
        val key = JSONObject.quote(InjectionScript.SELECTED_THEME_KEY)
        val marker = JSONObject.quote(InjectionScript.FORCED_THEME_MARKER_KEY)
        val body = if (forcedDark == null) handBackThemeJs(key, marker) else forceThemeJs(forcedDark, key, marker)
        return "(()=>{${InjectionScript.TOP_FRAME_GUARD}try{$body}catch(e){}})();"
    }

    /** Take ownership of the `dark` field, recording what it held so it can be handed back exactly. */
    private fun forceThemeJs(dark: Boolean, key: String, marker: String): String = (
        // The shim answers a `dark` query with the policy and a `light` query with its negation, so a
        // frontend asking either way gets one coherent answer. `no-preference` stays false, which is
        // what a real browser reports once a preference is stated.
        "var n=window.matchMedia&&window.matchMedia.bind(window);" +
            "if(n){window.matchMedia=function(q){var s=String(q);" +
            "var c=/^\\s*\\(\\s*prefers-color-scheme\\s*:\\s*(dark|light)\\s*\\)\\s*$/i.exec(s);" +
            "if(!c)return n(s);" +
            "var m=c[1].toLowerCase()==='dark'?$dark:${!dark};" +
            "return{media:s,matches:m,onchange:null," +
            // themes-mixin uses the DEPRECATED addListener, so both spellings must exist. They are
            // no-ops on purpose: a policy change rebuilds the WebView, so there is no change to emit.
            "addListener:function(){},removeListener:function(){}," +
            "addEventListener:function(){},removeEventListener:function(){}," +
            "dispatchEvent:function(){return false}}};}" +
            InjectionScript.readThemeStoreJs(key) +
            InjectionScript.RECOVER_THEME_STORE +
            // Snapshot once, on the first load under this policy. A later load must not re-snapshot, or
            // the recorded original would become the value this script itself wrote.
            "if(localStorage.getItem($marker)===null){" +
            "localStorage.setItem($marker,JSON.stringify({a:r===null,d:typeof o.dark==='boolean'?o.dark:null}))}" +
            InjectionScript.writeThemeDarkJs(key, dark)
        )

    /** Give the `dark` field back, and only that field; every other key in the object is the user's. */
    private fun handBackThemeJs(key: String, marker: String): String = (
        "var k=localStorage.getItem($marker);if(k===null)return;" +
            "var m={};try{m=JSON.parse(k)||{}}catch(e){}" +
            InjectionScript.readThemeStoreJs(key) +
            "if(o&&typeof o==='object'&&!(o instanceof Array)){" +
            // Absent originally means absent again: restoring `false` would invent a preference the
            // user never expressed, and `dark:false` is not the same as Auto to the frontend.
            "if(m.d===true||m.d===false)o.dark=m.d;else delete o.dark;" +
            "var c=0;for(var p in o)if(Object.prototype.hasOwnProperty.call(o,p))c++;" +
            // If ha-paneld created the whole entry and nothing but `dark` was ever in it, remove the
            // entry rather than leave an empty object where Home Assistant had stored nothing at all.
            "if(m.a===true&&c===0)localStorage.removeItem($key);" +
            "else localStorage.setItem($key,JSON.stringify(o))}" +
            "localStorage.removeItem($marker);"
        )

    /**
     * Reads HA's stored per-device theme (`s`) and the scheme the frontend is rendering now (`e`).
     * Both are booleans or null; nothing else from the page is returned, so the result is safe to log.
     * The effective value comes from `hass.themes.darkMode`, which is what the profile radio, the
     * server-side preference and `prefers-color-scheme` all resolve INTO — the one place the answer
     * to "what is on screen" is not a guess.
     */
    const val THEME_OBSERVATION_JS = """(function(){var s=null,e=null;try{var t=JSON.parse(localStorage.getItem('${InjectionScript.SELECTED_THEME_KEY}')||'null');s=t&&typeof t.dark==='boolean'?t.dark:null}catch(x){}try{var h=document.querySelector('home-assistant');var d=h&&h.hass&&h.hass.themes?h.hass.themes.darkMode:null;e=typeof d==='boolean'?d:null}catch(x){}return JSON.stringify({s:s,e:e})})()"""

    /** One theme observation; [valid] is false when the page returned something unparseable. */
    data class ThemeObservation(val storedDark: Boolean?, val effectiveDark: Boolean?, val valid: Boolean)

    /** Parse the `evaluateJavascript` result of [THEME_OBSERVATION_JS] (a JSON string, itself JSON-quoted). */
    fun parseThemeObservation(result: String?): ThemeObservation {
        val raw = result ?: return ThemeObservation(null, null, valid = false)
        return runCatching {
            // evaluateJavascript hands back the string VALUE as a JSON literal, so unquote it first.
            val inner = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
            val o = JSONObject(inner)
            fun field(k: String): Boolean? = if (o.isNull(k)) null else o.optBoolean(k).takeIf { o.get(k) is Boolean }
            ThemeObservation(field("s"), field("e"), valid = true)
        }.getOrElse { ThemeObservation(null, null, valid = false) }
    }

    /** Validate the fixed frontend callback before any token lookup or network refresh is attempted. */
    fun validAuthRequestForce(payload: String): Boolean? {
        if (payload.length > MAX_AUTH_PAYLOAD_CHARS) return null
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (message.opt("callback") != "externalAuthSetToken") return null
        return message.optBoolean("force", false)
    }


    /** `getExternalAuth` reply: `externalAuthSetToken(true, {access_token, expires_in})`, or a
     *  `(false)` failure when no token is available (unconfigured, or a refresh that failed closed).
     *  [expiresInSec] is the token's remaining life the frontend uses to know when to ask again. */
    fun authReply(payload: String, accessToken: String?, expiresInSec: Long): String? {
        if (callbackOf(payload) != "externalAuthSetToken") return null
        if (accessToken.isNullOrBlank()) return "externalAuthSetToken(false)"
        val auth = JSONObject().put("access_token", accessToken).put("expires_in", expiresInSec)
        return "externalAuthSetToken(true, $auth)"
    }

    /** `revokeExternalAuth` ack. The panel doesn't own the long-lived token (the admin minted it),
     *  so there's no server-side revoke to perform — acknowledge so the frontend can finish logout. */
    fun revokeReply(payload: String): String? =
        if (callbackOf(payload) == "externalAuthRevokeToken") "externalAuthRevokeToken(true)" else null

    /** Document-start script that forces panel-appropriate HA frontend prefs on this WebView's FIRST
     *  run, then never again: hide the sidebar, keep the websocket alive when idle, no haptics. Values
     *  are `JSON.stringify`'d to match HA's `ha-pref-storage` localStorage format. Self-gated by a
     *  sentinel key so it applies once, survives reloads/restarts, never clobbers a later user change,
     *  and re-applies after a renderer-storage wipe (a fresh first run). */
    fun panelDefaultsJs(): String =
        """(function(){try{
            ${InjectionScript.TOP_FRAME_GUARD}
            if(localStorage.getItem('__hapaneld_panel_defaults'))return;
            localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));
            localStorage.setItem('suspendWhenHidden',JSON.stringify(false));
            localStorage.setItem('vibrate',JSON.stringify(false));
            localStorage.setItem('__hapaneld_panel_defaults','1');
        }catch(e){}})();"""

    private fun callbackOf(payload: String): String =
        if (payload.length > MAX_AUTH_PAYLOAD_CHARS) ""
        else runCatching { JSONObject(payload).optString("callback") }.getOrDefault("")
}
