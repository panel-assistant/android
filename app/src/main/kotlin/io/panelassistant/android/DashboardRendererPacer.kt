package io.panelassistant.android

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import io.panelassistant.android.DashboardActivity.Companion.TAG
import io.panelassistant.android.control.BuiltinDashboard

/**
 * When the built-in dashboard renderer loads, retries, freezes and thaws: the frontend-handshake watchdog,
 * the dark settle window, the screen on/off fan-out, wake-media recovery and the memory ceiling. While the
 * WebView lives, it is the one place the process-wide WebView timers pause and resume; the activity's
 * teardown gives them their last thaw.
 *
 * [DashboardActivity] decides everything else from its [RendererPhase] and calls in here at each load,
 * reload, connection change and screen change; this reads the phase and the WebView back through [Host]
 * at call time, never captured, and posts on the activity's own Handler, so the activity's teardown still
 * clears every timer. The rules here are the hardware-proven never-blank ones: the watchdog runs only
 * while the screen is awake, a load started dark runs live through [DARK_SETTLE_MS] before freezing, a due
 * memory reload waits for a screen-off and runs behind it, and nothing retries while a login is refused.
 */
internal class DashboardRendererPacer(private val main: Handler, private val host: Host) {
    interface Host {
        val destroyed: Boolean

        /** Whether this activity still holds the renderer. */
        val owned: Boolean
        val phase: RendererPhase
        val web: WebView?

        /** The bare on-panel sign-in WebView is showing: no dashboard load, nothing to shed. */
        val signInShown: Boolean
        val rendererGeneration: Long
        fun rendererCurrent(generation: Long, view: WebView): Boolean

        /** A load is starting in the current WebView: the phase and what the page will carry. */
        fun loadStarting()

        /** The screen changed, whether or not a WebView exists. */
        fun screenChanged(awake: Boolean)

        /** A wake with a WebView up: someone is at the panel. */
        fun woke()

        /** The frontend handshake has not answered for so long that reloading again is pointless. */
        fun handshakeTimedOut(): Boolean

        /** Show the "Home Assistant is not responding" block for a handshake that never came. */
        fun blockHandshakeMissed()

        /** Announce a failed frontend's recovery before any replacement navigation. */
        fun recoveryStarting() {}

        /** A reload has already failed several times in a row: do more than reload. */
        fun escalate(step: HandshakeRecoveryStep)

        /** Load the current target again into the WebView; false when a native screen took over instead. */
        fun reloadTarget(): Boolean
    }

    val retryPolicy = DashboardRetryPolicy()
    val wakeMediaRecovery = WakeMediaRecoveryGate()

    /** The screen as this renderer last saw it, adopted at [start]. */
    var screenAwake = true
        private set

    // Frontend-handshake watchdog: the page "loading" (onPageFinished) is NOT health — the frontend JS
    // app must post `connection-status: connected` on the external bus. Until it does, we retry with
    // backoff (a panel has no buttons to press). This catches the "loaded onto a blank/dead frontend"
    // class that onPageFinished and the crash budget both miss. The watchdog runs ONLY while the screen is
    // awake: a screen-off pauses JS timers, so a paused WebView could never complete the handshake and the
    // watchdog would just reload-loop all night behind a dark panel.
    private val watchdog = Runnable { onWatchdogTimeout() }
    private var awaitingFrontendReconnect = false

    // After a screen-off reload (memory shed), let the fresh page load with timers LIVE for a settle
    // window, then freeze — so we never reload into a frozen WebView, but also never leave it churning
    // behind a dark screen forever. Connecting does not freeze it: the frontend reports connected before the
    // dashboard has drawn, so this window is the one freeze for every dark load.
    private val darkSettle = Runnable {
        if (!host.destroyed && host.owned && !screenAwake) {
            val phase = host.phase
            if (phase != RendererPhase.CONNECTED && phase != RendererPhase.AUTH_LATCHED) {
                Log.w(TAG, "dark settle: the dashboard did not connect within ${DARK_SETTLE_MS}ms — freezing until the screen wakes")
            }
            host.web?.let { it.onPause(); it.pauseTimers() }
        }
    }

    // Time-based memory ceiling. onTrimMemory's RUNNING_* levels are deprecated + never delivered on
    // Android 14, so a periodic check is the portable ceiling: reload invisibly at the next
    // screen-off past RELOAD_INTERVAL_MS, or force a (brief, visible) reload past RELOAD_HARD_MS so a
    // never-sleeping panel still sheds accreted WebView memory ~daily.
    private val periodicCheck = Runnable { onPeriodicCheck() }
    private var lastFullLoadAt = 0L
    private var reloadPending = false

    /**
     * Adopt the CURRENT screen state — an instance created while the panel is dark (a relaunch at night) must
     * not assume the screen is on: it would arm the handshake watchdog against a page that's about to be
     * frozen, and never freeze the WebView until the next real transition. Then start the memory ceiling.
     */
    fun start() {
        screenAwake = BuiltinDashboard.screenAwakeNow
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
    }

    /** Created dark: freeze after the settle window even if no dashboard load starts (the on-panel sign-in
     *  view never calls [loadStarted]). A load that does start re-arms this window itself. */
    fun settleIfCreatedDark() {
        if (!screenAwake) main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /** The WebView is going away (a teardown or a dead renderer): nothing of its may fire later. */
    fun stop() {
        awaitingFrontendReconnect = false
        main.removeCallbacks(watchdog)
        main.removeCallbacks(darkSettle)
    }

    /** Single scheduling authority for the post-reload settle window: cancel any pending settle and
     *  re-arm it [DARK_SETTLE_MS] out. See [darkSettle] for why a screen-off reload runs live for a
     *  window before freezing. */
    fun scheduleDarkSettle() {
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
    fun onScreenChanged(awake: Boolean) {
        if (host.destroyed || !host.owned) return
        val wasAwake = screenAwake
        screenAwake = awake
        host.screenChanged(awake)
        val w = host.web ?: return
        val phase = host.phase
        if (awake) {
            main.removeCallbacks(darkSettle)
            w.resumeTimers(); w.onResume()
            host.woke() // a wake implies presence — don't instantly snap home
            if (!wasAwake) {
                // A repeat "awake" changes nothing: re-arming here would cut a pending backoff or the
                // connected grace short. The sign-in has no handshake to wait for.
                if (phase.watched) armWatchdog(INITIAL_HANDSHAKE_MS)
                if (phase == RendererPhase.CONNECTED) armWakeMediaRecovery(w, host.rendererGeneration)
                else if (phase == RendererPhase.LOADING) wakeMediaRecovery.defer(host.rendererGeneration)
            }
        } else {
            wakeMediaRecovery.invalidate()
            main.removeCallbacks(watchdog) // a paused WebView can't complete the handshake — don't loop
            when (screenOffStep(phase == RendererPhase.CONNECTED, phase == RendererPhase.AUTH_LATCHED, reloadDue())) {
                ScreenOffStep.FREEZE -> { w.onPause(); w.pauseTimers() }
                // A load still in flight: leave the WebView live, untouched, so it can draw and connect.
                // darkSettle freezes it at the end of the window.
                ScreenOffStep.SETTLE -> {
                    Log.i(TAG, "screen off before the dashboard connected — keeping the load live for ${DARK_SETTLE_MS}ms")
                    scheduleDarkSettle()
                }
                ScreenOffStep.QUIET_RELOAD -> {
                    // Reload to shed memory, but DON'T pause timers yet — a paused WebView can't load. The dark
                    // load runs live and darkSettle freezes it after the settle window, so it doesn't churn all night.
                    reloadPending = false
                    reload("quiet reload (screen off, due)")
                }
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
                reload("visible media stalled after screen wake")
            }
        }
    }

    private fun wakeMediaCurrent(ticket: WakeMediaRecoveryTicket, w: WebView): Boolean =
        screenAwake && wakeMediaRecovery.owns(ticket) && host.rendererCurrent(ticket.rendererGeneration, w)

    /** A reload is worthwhile now if memory pressure asked for one, or it's been a long time since the
     *  last full load (WebView memory accretes over days). */
    private fun reloadDue(): Boolean =
        reloadPending || (SystemClock.elapsedRealtime() - lastFullLoadAt > RELOAD_INTERVAL_MS)

    /** Portable memory ceiling (see [periodicCheck]): reload invisibly at a screen-off past the interval,
     *  or force a brief visible reload past the hard cap so a panel that never sleeps still sheds memory. */
    private fun onPeriodicCheck() {
        if (host.destroyed || !host.owned) return
        if (host.phase == RendererPhase.AUTH_LATCHED) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        // The memory-ceiling reload measures idle from lastFullLoadAt, which the on-panel sign-in never
        // sets because it performs no dashboard load. On a panel that has been up for longer than the
        // interval that reads as "idle for the whole uptime", so the first check would reload — throwing
        // away a sign-in the user is part-way through, and reintroducing exactly the reload churn this
        // screen exists to end. Nothing here needs shedding: the sign-in page is a single small form.
        // No dashboard WebView (a status screen) has nothing to shed either; its next build is a fresh load.
        if (host.signInShown || host.web == null) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        val idle = SystemClock.elapsedRealtime() - lastFullLoadAt
        when {
            idle < RELOAD_INTERVAL_MS -> {}
            !screenAwake -> reload("periodic (screen off, ${idle / 3_600_000}h idle)")
            idle >= RELOAD_HARD_MS -> reload("periodic hard cap (${idle / 3_600_000}h idle)")
            else -> reloadPending = true // visible now — wait for the next screen-off to reload invisibly
        }
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
    }

    /** The activity's trim callback, past its maintenance fence. */
    fun onTrimMemory(level: Int) {
        if (host.destroyed || !host.owned || host.phase == RendererPhase.AUTH_LATCHED) return
        // The sign-in form has no accreted memory worth shedding, and reloading it would discard a
        // part-entered Home Assistant login. Leave it alone; it is short-lived by nature.
        if (host.signInShown) return
        // UI_HIDDEN is a routine lifecycle signal, NOT memory pressure — it's delivered on every
        // screen-off (activities stop when the display sleeps), so reacting to it made every screen-off
        // full-reload the frontend, defeating the 6-hour quiet-reload ceiling in onScreenChanged.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        // Any real trim signal means shed the WebView's accreted memory. Gate at RUNNING_LOW so we catch
        // BOTH the pre-14 foreground RUNNING_* levels AND the pressure levels Android 14 actually
        // delivers (BACKGROUND / MODERATE / COMPLETE, all ≥ RUNNING_LOW's value) — the previous
        // RUNNING_MODERATE..CRITICAL window was dead code on Android 14 panels.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            reloadPending = true
            // If we're not the visible dashboard, reload right now rather than risk an OOM renderer kill.
            if (!screenAwake || !BuiltinDashboard.foreground) {
                reloadPending = false
                reload("memory pressure L$level")
            }
        }
    }

    /** The activity's low-memory callback, past its maintenance fence. */
    fun onLowMemory() {
        if (!host.destroyed && host.owned && !host.signInShown && host.phase != RendererPhase.AUTH_LATCHED &&
            (!screenAwake || !BuiltinDashboard.foreground)) {
            reload("onLowMemory")
        }
    }

    /** Called after every page load that should be health-checked (initial, navigate, watchdog reload,
     *  network-regain reload): reset the connected state + arm the handshake watchdog — but only while the
     *  screen is awake, since a paused WebView can't run the JS that completes the handshake. */
    fun loadStarted(handshakeMs: Long = INITIAL_HANDSHAKE_MS) {
        awaitingFrontendReconnect = false
        wakeMediaRecovery.invalidate()
        host.loadStarting()
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordLoadStart(lastFullLoadAt) // TTI origin (first call since process start = cold)
        if (screenAwake) {
            // The activity this one replaced may have frozen the process's timers in the dark.
            resumeTimersIfOwner(host.web)
            armWatchdog(handshakeMs)
            return
        }
        // A load issued while dark (a remote reload, a rebuild) may land in a WebView that an earlier
        // screen-off froze, where it can never connect. Run it live through the settle window instead;
        // the watchdog stays off until the screen wakes, so a dark panel still never reload-loops.
        main.removeCallbacks(watchdog)
        // resumeTimers() is process-wide: a superseded instance must not unfreeze the owner's page.
        if (host.destroyed || !host.owned) return
        host.web?.let { it.onResume(); it.resumeTimers() }
        Log.i(TAG, "load started while the screen is dark — running it live for ${DARK_SETTLE_MS}ms")
        scheduleDarkSettle()
    }

    /** Reload through the one load-start path: lit, the handshake watchdog checks it; dark, it runs live
     *  through the settle window with the watchdog off, so a dark panel never reload-loops. */
    fun reload(reason: String) {
        Log.i(TAG, "reload: $reason")
        if (!host.reloadTarget()) return
        loadStarted()
    }

    /** `resumeTimers()` is process-wide, so only the renderer's owner unfreezes: a superseded instance must
     *  never wake its replacement's dark page. */
    fun resumeTimersIfOwner(view: WebView?) {
        if (host.destroyed || !host.owned) return
        view?.resumeTimers()
    }

    fun armWatchdog(ms: Long) {
        if (host.destroyed) return
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, ms)
    }

    fun disarmWatchdog() = main.removeCallbacks(watchdog)

    /**
     * The frontend reported itself connected for [generation]: the handshake is done, its cadence starts
     * over, and a wake that came while it was still loading gets its media check now.
     */
    fun onConnected(generation: Long) {
        awaitingFrontendReconnect = false
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
            host.web?.let { w -> armWakeMediaRecovery(w, generation, ticket) }
        }
    }

    /** The frontend dropped its connection ([what] says how): give its own reconnect a grace window, then
     *  reload — but only while awake (a frozen WebView can't reconnect; wake re-arms the handshake watchdog). */
    fun onDisconnected(wasConnected: Boolean, what: String) {
        if (screenAwake && host.phase != RendererPhase.AUTH_LATCHED) {
            // Repeated websocket events belong to the same outage. They must neither shorten the
            // connected page's grace to the initial retry delay nor extend it indefinitely.
            if (awaitingFrontendReconnect) return
            awaitingFrontendReconnect = wasConnected
            val delay = retryPolicy.connectionFailureDelay(wasConnected)
            Log.i(TAG, "frontend '$what' — arming retry watchdog in ${delay}ms (wasConnected=$wasConnected)")
            armWatchdog(delay)
        }
    }

    private fun onWatchdogTimeout() {
        // Never retry on a dead activity or latched — both runaway loops. While frozen (screen off) the
        // policy answers NONE: a paused page cannot complete the handshake, so that fire is not a miss.
        if (host.destroyed || !host.owned || !host.phase.watched) return
        val step = retryPolicy.onWatchdogFired(screenAwake)
        if (step == HandshakeRecoveryStep.NONE) return
        if (host.handshakeTimedOut()) {
            host.blockHandshakeMissed()
            return
        }
        host.recoveryStarting()
        if (step != HandshakeRecoveryStep.RELOAD) {
            host.escalate(step)
            return
        }
        Log.w(TAG, "frontend handshake watchdog fired (no connection-status:connected) — reloading")
        // The reconnect grace expired: this load now owns recovery, so a later connection callback must
        // not revive the pre-timeout wake ticket against the replacement target.
        wakeMediaRecovery.invalidate()
        if (!host.reloadTarget()) return
        BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary: handshake stalled
        loadStarted(retryPolicy.afterRetry())
    }

    companion object {
        const val INITIAL_HANDSHAKE_MS = 25_000L        // generous: a cold PX30 frontend can need 20s+
        private const val RELOAD_INTERVAL_MS = 6 * 60 * 60 * 1000L   // shed WebView memory at a screen-off, ~6h
        private const val RELOAD_HARD_MS = 26 * 60 * 60 * 1000L      // force a visible reload if never idle-dark
        private const val PERIODIC_CHECK_MS = 30 * 60 * 1000L        // how often the memory-ceiling check runs
        private const val DARK_SETTLE_MS = 30_000L              // let a screen-off reload load before freezing
        private const val WAKE_MEDIA_SETTLE_MS = 3_000L         // let resumeTimers/onResume reach the page first
        private const val WAKE_MEDIA_SAMPLE_MS = 6_000L         // tolerate slow camera transport reconstruction
    }
}
