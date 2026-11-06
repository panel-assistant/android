package io.panelassistant.android

import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.LocalAdminEndpoint
import io.panelassistant.android.util.localIpv4
import io.panelassistant.android.util.localIpv6
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * The native screens [DashboardActivity] shows instead of the dashboard: the one branded status frame,
 * the admission verdicts with their retry countdown, the wait for Android's network at startup, and the
 * compact deliberate-reload notice. The activity decides WHEN a screen is due and owns the recovery a
 * screen's timer or button runs; this draws the screen and keeps its timer. Every timer posts on the
 * activity's own [main] Handler, so the activity's `removeCallbacksAndMessages(null)` at destroy still
 * clears all of them.
 */
internal class DashboardStatusScreens(
    private val activity: AppCompatActivity,
    private val main: Handler,
    private val scope: CoroutineScope,
    /** Shared with the activity, which resets it when admission succeeds or the dashboard owner changes. */
    private val admissionRetryPolicy: AdmissionRetryPolicy,
    private val host: Host,
    /** The panel's sign-in address for a phone at a path, or null when it has none a phone can reach. */
    private val signInAddress: (path: String) -> String? = {
        LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), Config(activity).httpPort, it)
    },
    private val addressReader: CoroutineDispatcher = Dispatchers.IO,
    private val screenAwake: () -> Boolean = { true },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val postWaiting: (Runnable, Long) -> Unit = { tick, delay -> main.postDelayed(tick, delay) },
    private val cancelWaiting: (Runnable) -> Unit = { main.removeCallbacks(it) },
    private val paintWaitingHero: (TextView, String) -> Unit = { view, text -> view.text = text },
) {
    interface Host {
        val activityOwner: Long
        val destroyed: Boolean
        val hasWeb: Boolean
        fun teardownWeb()
        /** The view the activity last installed as its content. */
        var root: FrameLayout?
        fun detachLifecycleBar()
        fun activeNetworkPresent(): Boolean
        /** The one admission retry sequence, shared by the Retry button ([resetBackoff] true, which also
         *  forgets a refused login) and the automatic timer (false). */
        fun retryAdmission(resetBackoff: Boolean)
        /** The automatic admission timer is due. */
        fun onAdmissionRetryDue()
        /** No native status screen is up: redraw whichever page the dashboard WebView is showing. */
        fun redrawCoverPage()
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

    // The retry deadline and the visible countdown are owned separately on purpose: the countdown may
    // legitimately stop while the retry must not. Uptime is the Handler's own clock, so a deep sleep
    // freezes the displayed figure and the pending callback together.
    private val admissionCountdown = AdmissionCountdownOwner { SystemClock.uptimeMillis() }
    private var admissionCountdownView: TextView? = null
    // One automatic-recovery owner for EVERY blocked admission screen. A wall panel has nobody
    // standing at it, so a blocked screen without a timer stays blocked after its cause clears —
    // recoverable only by a physical tap or an app restart. The timer and the manual Retry button run
    // the same sequence; the button also resets the back-off because a present human should not wait
    // out a timer.
    private val admissionRetry = Runnable { host.onAdmissionRetryDue() }
    private val admissionCountdownTick = Runnable { onAdmissionCountdownTick() }

    private val signInPageCheck by lazy { SignInPageCheck(scope) }

    private var waitingStatus: TextView? = null
    private var waitingStage: TextView? = null
    private var waitingTrack: NoticeTrack? = null
    private var waitingHero: TextView? = null
    private var waitingEstimateMs = 0L
    private var waitingEstimateLearned = false
    private var waitingStartedAt = 0L
    private val waitingTick = object : Runnable {
        override fun run() {
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || waitingStatus == null || !screenAwake()) return
            val elapsed = now() - waitingStartedAt
            val expected = (waitingEstimateMs + 500L) / 1_000L
            waitingStatus?.text = if (waitingEstimateLearned) {
                activity.getString(R.string.elapsed_usually, elapsed / 1_000L, expected)
            } else {
                activity.getString(R.string.elapsed_learning, elapsed / 1_000L)
            }
            val snapshot = startupNetworkSnapshot()
            val notice = networkWaitNotice(snapshot, elapsed, waitingEstimateMs, waitingEstimateLearned)
            waitingHero?.let { paintWaitingHero(it, haLifecycleClock(notice.heroMs)) }
            waitingTrack?.show(notice.step, notice.fills)
            statusSurface?.notice?.setPill(activity.getString(NETWORK_STEPS[notice.step]))
            waitingStage?.text = localizedStartupNetworkStage(snapshot)
            postWaiting(this, 1_000L)
        }
    }

    /** The compact notice announcing a deliberate reload, while it is up. */
    private var reloadNotice: View? = null

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
     * [io.panelassistant.android.control.DeliberateRestartAnnouncements].
     */
    fun announceDeliberateRestart(
        reason: String,
        learning: Boolean = reason == BuiltinDashboard.LEARNING_RELOAD_REASON,
    ) {
        if (!BuiltinDashboard.restartAnnouncements.shouldAnnounce(
                learning = learning,
                dashboardShown = host.hasWeb,
                nowMs = SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "learning restart not announced again (${if (!host.hasWeb) "no dashboard shown" else "learning window open"})")
            return
        }
        reloadNotice = showReloadCard(activity, reloadNotice, reloadNoticeReason(reason)?.let(activity::getString) ?: reason)
    }

    fun clearReloadNotice() { reloadNotice?.let { (it.parent as? ViewGroup)?.removeView(it) }; reloadNotice = null }

    /** The panel's own sign-in setting at [path], when it answers there; see [SignInPageCheck]. */
    fun provenSignInUrl(path: String, redraw: () -> Unit): String? {
        val address = signInAddress(path)
        watchSignInAddress(path, address, redraw)
        return signInPageCheck.proven(address, redraw)
    }

    private var signInAddressWatch: Job? = null

    /**
     * While a surface showing the sign-in address is up, re-read the address every
     * [SIGN_IN_ADDRESS_CHECK_MS] off the main thread (as the standing screen does) and redraw the surface
     * when it moved, so its code and fix line never keep naming an address the panel no longer has. The
     * redraw draws through [provenSignInUrl] again, which re-arms this for the new address.
     */
    private fun watchSignInAddress(path: String, drawn: String?, redraw: () -> Unit) {
        signInAddressWatch?.cancel()
        signInAddressWatch = scope.launch {
            do delay(SIGN_IN_ADDRESS_CHECK_MS) while (withContext(addressReader) { signInAddress(path) } == drawn)
            signInAddressWatch = null
            if (!host.destroyed) redraw()
        }
    }

    /** The surface showing the sign-in address went down. */
    fun stopSignInAddressWatch() {
        signInAddressWatch?.cancel()
        signInAddressWatch = null
    }

    /** Redraw every ha-paneld status surface that is currently rendered, native or WebView-drawn. */
    fun convergeStatusTheme() {
        statusSurface = null
        val redraw = statusRerender
        if (redraw != null) {
            host.root = null
            redraw()
            return
        }
        // No native status screen is installed, so the only ha-paneld-owned surfaces that can be on
        // screen are the two pages drawn into the dashboard WebView. Re-issue whichever is showing.
        host.redrawCoverPage()
    }

    /** This activity handles orientation, screen size and night mode itself rather than being
     *  recreated, so a status screen that is up stays as drawn unless it is redrawn here — but only
     *  when something it depends on actually moved. It also receives locale, keyboard and font-scale
     *  events, and rebuilding a live surface for those is churn a user can see. */
    fun onConfigurationChanged() {
        val geometry = StatusSurface.geometryFor(activity)
        val dark = StatusSurface.darkFor(activity, Config(activity))
        val current = statusSurface
        if (current == null || !statusSurfaceReusable(current.geometry, current.dark, geometry, dark)) {
            convergeStatusTheme()
        }
    }

    /** The content view is no longer a status screen (the dashboard or the on-panel sign-in replaced it). */
    fun clearRerender() {
        statusRerender = null
        stopSignInAddressWatch()
    }

    fun statusSurface(config: Config = Config(activity)): StatusSurface {
        val dark = StatusSurface.darkFor(activity, config)
        val geometry = StatusSurface.geometryFor(activity)
        statusSurface?.let { if (statusSurfaceReusable(it.geometry, it.dark, geometry, dark)) return it }
        return StatusSurface(activity, dark).also { statusSurface = it }
    }

    /**
     * Install the frame only when it is not already installed.
     *
     * Caching the instance is not enough on its own: `setContentView` clears the content parent
     * before adding, so handing it the same root again detaches and reattaches the header the frame
     * exists to hold still. This activity declares `orientation|screenSize|uiMode` in its
     * `configChanges`, so it also survives rotations that would otherwise have recreated it.
     */
    fun showStatusSurface(surface: StatusSurface, rerender: () -> Unit) {
        // Every native status screen is installed here, so this is also the one place that has to
        // disarm the admission auto-retry: a screen that has been replaced must not keep a timer
        // running against the view tree it left behind. A caller that means to keep its pending retry
        // reads the remaining time first and re-arms it afterwards.
        cancelAdmissionAutoRetry()
        clearReloadNotice() // a status screen replaced the dashboard the card was about
        statusRerender = rerender
        if (statusSurfaceAlreadyInstalled(host.root, surface.root)) return
        host.root = surface.root
        activity.setContentView(surface.root)
    }

    /** One quiet startup state while Android brings networking up. This is native rather than cached
     *  WebView content, so neither Chromium's error UI nor HA's 60-second reconnect page can appear. */
    fun showWaitingForNetwork() {
        host.detachLifecycleBar()
        val config = Config(activity)
        waitingEstimateLearned = config.lastNetworkWaitMs > 0L
        waitingEstimateMs = config.lastNetworkWaitMs.takeIf { it > 0L } ?: DEFAULT_NETWORK_WAIT_MS
        val surface = statusSurface(config)
        surface.setBrandCaption(activity.getString(R.string.service_running_caption))
        val notice = surface.notice
        val hero = notice.hero("")
        val stage = notice.line(localizedStartupNetworkStage(startupNetworkSnapshot()))
        val track = notice.track(NETWORK_STEPS.map(activity::getString), 1_000)
        val status = notice.footer("")
        surface.setBody(activity.getString(NETWORK_STEPS[0]), hero, stage, track.view, status)
        showStatusSurface(surface) { showWaitingForNetwork() }
        waitingStatus = status
        waitingStage = stage
        waitingTrack = track
        waitingHero = hero
        // Redrawing after a rotation must not restart the clock — the panel has been waiting exactly
        // as long as it was a moment ago, and the learned estimate is measured from this.
        if (waitingStartedAt == 0L) waitingStartedAt = now()
        // Cancel before starting: a redraw used to leave the previous once-per-second chain running,
        // so every configuration change added a permanent timer until the network recovered.
        cancelWaiting(waitingTick)
        waitingTick.run()
        Log.i(TAG, "no default network at startup — waiting before creating WebView")
    }

    /** Suspend only the visible clock; network recovery and its elapsed origin keep running. */
    fun screenChanged(awake: Boolean) {
        if (waitingStatus == null) return
        cancelWaiting(waitingTick)
        if (awake) waitingTick.run()
    }

    /** The default network arrived: stop the wait and its once-per-second update, learn how long it took
     *  for the next boot, and return that. */
    fun endNetworkWait(): Long {
        val waitMs = (SystemClock.elapsedRealtime() - waitingStartedAt).coerceAtLeast(0L)
        if (waitingStartedAt > 0L) Config(activity).setLastNetworkWaitMs(waitMs)
        waitingStartedAt = 0L
        if (waitingStatus != null) cancelWaiting(waitingTick)
        waitingStatus = null
        waitingStage = null
        waitingTrack = null
        waitingHero = null
        return waitMs
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
            defaultNetwork = host.activeNetworkPresent(),
        )
    }

    private fun localizedStartupNetworkStage(snapshot: StartupNetworkSnapshot): String = activity.getString(
        when {
            !snapshot.interfacePresent -> R.string.network_starting_android
            !snapshot.linkUp -> R.string.network_waiting_link
            !snapshot.addressAssigned -> R.string.network_waiting_address
            !snapshot.defaultNetwork -> R.string.network_preparing_connection
            else -> R.string.network_opening_ha
        },
    )

    /** A blocked admission screen. [outcome] is not defaulted: every blocked screen must say what the
     *  panel learned, and [admissionRetryClass] — not this call site — decides whether that recovers on
     *  its own. Progress screens use [showAdmissionProgressScreen] instead.
     *
     *  This is also the single site that publishes a blocked verdict to [RendererAdmissionRuntime], so
     *  a screen cannot reach the panel without the diagnostic surfaces learning about it. [evidence] is
     *  the classified transport failure where one was observed; the raw [detail] stays on the panel and
     *  never enters the runtime, because it can embed the configured Home Assistant host. */
    fun showBlockedAdmissionScreen(
        title: String,
        detail: String,
        outcome: AdmissionOutcome,
        evidence: HaTransportEvidence = HaTransportEvidence.NONE,
        /** The hero of a screen that neither retries on its own nor offers a QR: a number or a word. */
        heroWord: String? = null,
    ) {
        RendererAdmissionRuntime.record(
            owner = host.activityOwner,
            state = RendererAdmissionState.BLOCKED,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            outcome = outcome,
            evidence = evidence,
        )
        showV2CompatibilityScreen(title, detail, activity.getString(R.string.retry), admissionRetryClass(outcome), outcome, heroWord, title)
    }

    /** A screen that reports work in flight; it is replaced by that work's outcome, so it never arms. Its
     *  title is shown split, as a hero word and the line under it. */
    fun showAdmissionProgressScreen(@StringRes title: Int, @StringRes hero: Int, @StringRes line: Int, @StringRes detail: Int) {
        RendererAdmissionRuntime.record(
            owner = host.activityOwner,
            state = RendererAdmissionState.CHECKING,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        showV2CompatibilityScreen(activity.getString(title), activity.getString(detail), null, AdmissionRetryClass.MANUAL_ONLY, null, activity.getString(hero), activity.getString(line))
    }

    fun showV2CompatibilityScreen(
        title: String,
        detail: String,
        retryLabel: String?,
        autoRetry: AdmissionRetryClass,
        outcome: AdmissionOutcome?,
        heroWord: String?,
        line: String,
    ) = paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, heroWord, line, rearm = true)

    /**
     * [rearm] is false only when the screen already on display is being redrawn for a panel-theme or
     * system configuration change. The retry deadline and the back-off ladder belong to the admission
     * failure, not to the drawing of it: re-arming on every repaint would advance the ladder and push
     * the deadline further out each time, so a panel being rotated could postpone its own recovery
     * indefinitely. The pending deadline is therefore carried across the redraw unchanged, while the
     * countdown itself is rebuilt because the previous one belongs to a discarded view tree.
     */
    private fun paintV2CompatibilityScreen(
        title: String,
        detail: String,
        retryLabel: String?,
        autoRetry: AdmissionRetryClass,
        outcome: AdmissionOutcome?,
        heroWord: String?,
        line: String,
        rearm: Boolean,
    ) {
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return
        // Read before the shared installer disarms, and read as an INSTANT: everything between here
        // and the re-arm below — building the views, laying them out, installing them — is time that a
        // carried duration would add to the deadline on every single repaint.
        val carriedDeadlineMs = if (rearm) null else admissionCountdown.deadlineAtMs
        if (host.hasWeb) host.teardownWeb()
        val surface = statusSurface()
        val notice = surface.notice
        // Credential failures can offer sign-in on a phone instead of opening the local browser.
        val phoneUrl = configureQrPath(outcome)?.let { path -> provenSignInUrl(path) { statusRerender?.invoke() } }
        val qrSlots = admissionQrSlots(outcome)
        val qr = if (phoneUrl != null && qrSlots != null) {
            notice.qr(phoneUrl, activity.getString(R.string.config_qr_description, phoneUrl), activity.getString(qrSlots.prompt))
        } else null
        val buttons = buildList {
            retryLabel?.let { label ->
                add(notice.action(label, primary = true) {
                    host.retryAdmission(resetBackoff = true)
                })
            }
            // The code replaces Configure rather than joining it: both open the same page, and the
            // whole reason the code is here is that this page is the one worth doing on a phone.
            if (qr == null) {
                add(notice.action(activity.getString(R.string.configure)) {
                    activity.startActivity(Intent(activity, ConfigActivity::class.java).putExtra("path", "/configure"))
                })
            }
        }
        // The retry countdown is the hero: a real number, not a spinner. The jittered delay is computed
        // once, here, and the hero counts down to that exact figure. A redraw computes nothing: it already
        // has a deadline, and only the view needs rebuilding.
        val autoRetryDelayMs = if (carriedDeadlineMs != null) null
            else if (retryLabel == null) null else admissionRetryPolicy.nextDelayMs(autoRetry)
        val countdown = if (carriedDeadlineMs != null || autoRetryDelayMs != null) notice.hero("") else null
        // A sign-in keeps its pill whether or not the code can be offered: the page behind it may simply not
        // answer from the network.
        val pill = admissionNoticePill(progress = retryLabel == null, signIn = qrSlots != null, retries = countdown != null)
        surface.setBody(
            activity.getString(pill),
            qr ?: countdown ?: heroWord?.let { notice.hero(it, word = true) },
            notice.line(if (qr != null && qrSlots != null) activity.getString(qrSlots.line) else line),
            if (buttons.isEmpty()) null else notice.actions(*buttons.toTypedArray()),
            // The address under a code is the route when the code cannot be scanned.
            notice.footer(if (qr != null) phoneUrl!! else detail),
        )
        // Preserve the outcome so a rotation or theme change keeps the same sign-in guidance.
        showStatusSurface(surface) {
            paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, heroWord, line, rearm = false)
        }
        admissionCountdownView = countdown
        if (carriedDeadlineMs != null) resumeAdmissionAutoRetry(carriedDeadlineMs, title)
        else if (autoRetryDelayMs != null) armAdmissionAutoRetry(autoRetryDelayMs, title)
        Log.w(TAG, "$title: $detail")
    }

    fun localizedHaUnavailableDetail(blocked: DashboardV2ProbeResult.Unavailable): String =
        activity.getString(
            R.string.cannot_reach_ha_detail,
            activity.localizedHaTransportFault(blocked.evidence.fault),
            blocked.detail,
        )

    /** Disarm the admission auto-retry and its countdown. Every function that replaces the activity's
     *  content view MUST call this first: the timer belongs to the blocked screen that armed it, and a
     *  survivor firing under a healthy dashboard would tear that dashboard down to re-probe. */
    fun cancelAdmissionAutoRetry() {
        admissionCountdown.disarm()
        admissionCountdownView = null
        main.removeCallbacks(admissionRetry)
        main.removeCallbacks(admissionCountdownTick)
    }

    fun armAdmissionAutoRetry(delayMs: Long, title: String) {
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
        paint.remainingMs?.let { remaining -> view.text = haLifecycleClock(remaining) }
        paint.scheduleNextTickMs?.let { main.postDelayed(admissionCountdownTick, it) }
    }

    private fun onAdmissionCountdownTick() {
        if (host.destroyed) return
        applyAdmissionPaint(admissionCountdown.onTick())
    }

    /** Top-visibility, not merely resumed: a translucent Overview can take top-resumed status without
     *  calling onPause, and repainting behind it is invisible work. The retry stays armed throughout. */
    fun onAdmissionVisibilityChanged(visible: Boolean) {
        applyAdmissionPaint(admissionCountdown.onVisibilityChanged(visible))
    }

    private companion object {
        const val TAG = DashboardActivity.TAG
        const val DEFAULT_NETWORK_WAIT_MS = 60_000L     // calm first-boot progress until learned
        const val SIGN_IN_ADDRESS_CHECK_MS = 3_000L     // the standing screen's address re-check cadence
    }
}
