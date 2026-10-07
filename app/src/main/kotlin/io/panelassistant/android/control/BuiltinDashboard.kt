package io.panelassistant.android.control


/**
 * Process-local foreground state of the built-in [io.panelassistant.android.DashboardActivity].
 *
 * The built-in renderer runs inside ha-paneld's own process, so — unlike a foreign dashboard app —
 * its liveness/foreground can be read directly from an activity lifecycle flag instead of probed with
 * root `pidof`/`dumpsys`. [SystemController.dashboardState] reads this for the `builtin` dashboard so
 * the watchdog + kiosk return-loop work with zero root and zero IPC. Pure Kotlin (no Android imports)
 * so the controller stays unit-testable.
 */
object BuiltinDashboard {
    // Activity lifetimes can overlap while Android replaces a task. Global renderer state therefore
    // belongs to the newest activity lease: a late onPause/onDestroy from its predecessor must not mark
    // the replacement background or clear its authentication warning.
    private var nextActivityOwner = 0L
    @Volatile private var activityOwner = 0L

    @Synchronized fun acquireActivityOwner(): Long {
        val owner = ++nextActivityOwner
        activityOwner = owner
        authLatched = false
        return owner
    }

    @Synchronized fun ownsActivity(owner: Long): Boolean = owner != 0L && activityOwner == owner

    @Synchronized fun setActivityForeground(owner: Long, value: Boolean) {
        if (activityOwner == owner) foreground = value
    }

    @Synchronized fun setActivityAuthLatched(owner: Long, value: Boolean) {
        if (activityOwner == owner) authLatched = value
    }

    /**
     * The effective dashboard theme policy baked into the live WebView, or null when no renderer
     * generation holds one. The Ambient theme compares its resolved policy against this, so a new
     * verdict rebuilds the dashboard only when the page on screen does not already carry it.
     */
    @Volatile var appliedThemeSignature: String? = null
        private set

    @Synchronized fun setAppliedThemeSignature(owner: Long, signature: String) {
        if (activityOwner == owner) appliedThemeSignature = signature
    }

    @Synchronized fun releaseActivityOwner(owner: Long) {
        if (activityOwner != owner) return
        activityOwner = 0L
        appliedThemeSignature = null
        authLatched = false
        foreground = false
    }

    // --- foreground state + change fan-out (navbar overlay-strip suppression) ---
    //
    // While the built-in renderer is foreground, [NavbarController] must NOT arm its full-width bottom
    // overlay strip (which consumes every touch in its 48dp band) — the renderer detects the
    // swipe-reveal gesture in-activity instead (zero-latency taps, no root). The controller registers
    // here to learn foreground changes; the fan-out fires on CHANGE only (all writers are main-thread
    // lifecycle callbacks, so the read-modify-write is safe without extra locking).
    @Volatile private var foregroundListener: ((Boolean) -> Unit)? = null

    /** [NavbarController] registers its strip suppress/re-arm handler here. */
    fun setForegroundListener(l: ((Boolean) -> Unit)?) { foregroundListener = l }

    /** True while DashboardActivity is resumed (foreground). Set from its onResume/onPause. */
    @Volatile var foreground = false
        private set(value) {
            val changed = field != value
            field = value
            if (changed) foregroundListener?.invoke(value)
            if (changed && value) foregroundGainedListener?.invoke()
        }

    // A second, independent subscriber: the Ambient theme re-checks the page's baked scheme each time
    // the dashboard returns to the front, because a verdict reached while it was paused (screen off,
    // another app on top) had nothing in front to rebuild. Kept apart from [foregroundListener] so
    // neither owner can displace the other.
    @Volatile private var foregroundGainedListener: (() -> Unit)? = null

    fun setForegroundGainedListener(l: (() -> Unit)?) { foregroundGainedListener = l }

    // --- navbar swipe-reveal handoff ---
    //
    // Non-null exactly while navbar mode is "Swipe reveal" (set/cleared by [NavbarController.apply]), so
    // the renderer's edge detector arms only when a reveal can actually happen — never steals a scroll
    // gesture for a no-op. The renderer's `BottomSwipeFrame` reads [navbarSwipeEnabled] at each DOWN and
    // calls [requestNavbarReveal] on a qualifying edge-swipe.
    @Volatile private var navbarReveal: (() -> Unit)? = null

    /** [NavbarController.apply] publishes its reveal trigger here for "Swipe reveal" mode, null otherwise. */
    fun setNavbarRevealHandler(h: (() -> Unit)?) { navbarReveal = h }

    /** True while a bottom-edge swipe should reveal the soft navbar (i.e. navbar mode is "Swipe reveal"). */
    val navbarSwipeEnabled: Boolean get() = navbarReveal != null

    /** Fired by the renderer's `BottomSwipeFrame` when a bottom-edge swipe qualifies; no-op if unset. */
    fun requestNavbarReveal() { navbarReveal?.invoke() }

    /** The dashboard path the renderer should show — set by an MQTT `navigate` command, then the
     *  renderer is (re)launched and reads this on load. Null = fall back to the configured home
     *  dashboard. Lets `navigate` drive the built-in WebView the way a deep link drives the Companion. */
    @Volatile var navPath: String? = null

    /** Consume-once read of [navPath]: a navigate is a one-shot target, not a standing state — without
     *  this, one MQTT navigate becomes the permanent reload target of every later crash rebuild and
     *  interstitial recovery (which should return to the configured home instead). */
    @Synchronized fun consumeNavPath(): String? = navPath.also { navPath = null }

    // A singleTask relaunch reaching the live renderer can mean two different things: "reload the page"
    // (an explicit reload command / changed credentials) or merely "come to the foreground" (the kiosk /
    // watchdog return loops). The launch travels through `am start`, which can't carry that distinction
    // reliably — instead reload requesters set this in-process flag just before launching, and the
    // renderer's onNewIntent consumes it. Absent flag + healthy page = foreground only, so a kiosk
    // snap-back no longer blanks the dashboard with a full reload.
    @Volatile private var reloadRequested = false

    @Volatile private var reloadReason = ""

    /** Deliberate user retry: atomically restore the renderer budget and make the next intent reload.
     *  [reason] is shown natively on the panel before the reset (maintainer rule: every deliberate
     *  dashboard restart announces itself, or on-purpose resets read as crashes and the built-in
     *  renderer earns a reputation for unreliability one report at a time). */
    @Synchronized
    fun requestExplicitReload(reason: String = "") {
        rendererLatchedUntil = 0L
        rebuilds = 0
        rebuildWindowStart = 0L
        reloadRequested = true
        reloadReason = reason
    }

    @Synchronized fun consumeReloadRequest(): Boolean = reloadRequested.also { reloadRequested = false }

    @Synchronized fun consumeReloadReason(): String = reloadReason.also { reloadReason = "" }

    /** A full renderer rebuild already satisfies any pending reload request. Consume it with its reason,
     *  or the next plain bring-to-foreground reloads the fresh page again and announces a second restart. */
    @Synchronized fun consumeSupersededReload(): String {
        reloadRequested = false
        return consumeReloadReason()
    }

    /** Reload reason the entity-filter learner passes when a learned set changes. */
    const val LEARNING_RELOAD_REASON = "applying the entity filter"

    /** Process-global, like the rebuild budget below: a recreated renderer keeps the learning window. */
    val restartAnnouncements = DeliberateRestartAnnouncements(learningWindowMs = 10L * 60_000)

    // --- renderer crash-loop budget + latch (process-global, NOT per-activity-instance) ---
    //
    // The budget must outlive the activity: a crash fallback finishes the activity, the kiosk/watchdog
    // return loop relaunches a fresh instance, and a per-instance counter resets — an infinite
    // fallback/relaunch churn with no backoff. Held here so every instance shares one budget, and when
    // it's exhausted the renderer LATCHES: [SystemController.dashboardState] reports DEAD so automatic
    // recovery loops stand down, and startBuiltin refuses automatic relaunches until the latch expires.
    // Health reads this latch directly rather than feeding it through the foreign-app crash budget. An
    // explicit reload (MQTT/`:8888`/navbar) clears the latch —
    // deliberate user action is always honoured. Time params are caller-supplied elapsed-realtime
    // millis so this object stays pure Kotlin (JVM-unit-testable).
    private var rebuilds = 0
    private var rebuildWindowStart = 0L
    @Volatile private var rendererLatchedUntil = 0L

    /** Spend one renderer (re)build from the budget; false = budget exhausted, latch engaged. */
    @Synchronized fun consumeRebuildBudget(now: Long): Boolean {
        if (now - rebuildWindowStart > REBUILD_WINDOW_MS) { rebuildWindowStart = now; rebuilds = 0 }
        if (++rebuilds <= MAX_REBUILDS) return true
        rendererLatchedUntil = now + RENDERER_LATCH_MS
        return false
    }

    /** True while automatic relaunches are suppressed after a crash-loop (latch not yet expired). */
    fun rendererLatched(now: Long): Boolean = now < rendererLatchedUntil

    /** Clear the crash-loop latch + budget — called on an explicit reload (deliberate retry consent). */
    @Synchronized fun clearRendererLatch() { rendererLatchedUntil = 0L; rebuilds = 0; rebuildWindowStart = 0L }

    const val MAX_REBUILDS = 3
    const val REBUILD_WINDOW_MS = 60_000L
    const val RENDERER_LATCH_MS = 10 * 60_000L      // one 3-attempt burst per 10 min, not per 1.2s poll

    // --- built-in renderer responsiveness self-measurement (time-to-interactive + reload churn) ---
    //
    // The built-in and Companion renderers share the same system WebView engine, so raw paint speed is
    // identical — a real responsiveness difference comes from event-processing headroom and host-app
    // footprint, not rendering. These two cheap in-process measurements let a user read a before/after
    // number when they switch renderers (surfaced on `/api/v1/perf`), and let traditional (non-firehose-
    // stripped) setups produce their own evidence.
    //
    // Time-to-interactive = the gap from a page load STARTING (DashboardActivity.onLoadStarted) to the
    // frontend reporting `connection-status: connected` (onConnectionStatus) — the app's own "websocket
    // up, cards rendering" signal, the closest analog to the Companion's connected state. The COLD number
    // is launch → first-connected (the meaningful renderer-comparison figure, measured from the FIRST
    // load-start since process start so a failed-then-retried initial load still counts full launch time);
    // navigate / reload / crash-rebuild loads are WARM. Times are caller-supplied elapsed-realtime millis
    // so this stays pure Kotlin (JVM-unit-testable); a non-positive or absurd gap is dropped.
    private const val TTI_RING = 10
    private const val TTI_MAX_MS = 120_000L         // a gap beyond 2 min is a watchdog/stall artefact, not a load
    private var firstLoadStartAt = -1L              // first load-start since process start (drives the cold number)
    private var lastLoadStartAt = -1L               // most-recent load-start (drives warm navigate/reload TTIs)
    private var coldTtiMs = -1L                      // launch → first-connected (ms); -1 until captured
    private var coldCaptured = false
    private val warmTti = ArrayDeque<Long>()        // recent WARM durations (ms), newest last, capped at TTI_RING

    /** Record a page load STARTING. The first call since process start pins the cold-load origin. Main-thread. */
    @Synchronized fun recordLoadStart(nowMs: Long) {
        if (firstLoadStartAt < 0) firstLoadStartAt = nowMs
        lastLoadStartAt = nowMs
    }

    /** Record the frontend reaching `connected`. The first connect since process start closes the cold
     *  window (diffed against the launch origin); later connects are warm (diffed against the pending
     *  load-start, consumed so a duplicate `connected` can't double-count). Out-of-range gaps are dropped. */
    @Synchronized fun recordConnected(nowMs: Long) {
        // Setup's proof that a dashboard actually rendered. Home Assistant's own frontend reporting
        // `connected` means the WebView was built, its bundle executed and its websocket authenticated —
        // the strongest evidence available without inspecting pixels. It is NOT proof that a Lovelace card
        // painted (a broken dashboard config could still show an error view), so callers must describe it
        // as "Home Assistant is rendering in the panel", not "your dashboard looks right".
        // Recorded unconditionally, ahead of the TTI bookkeeping below, which deliberately drops bare
        // reconnects that have no pending load to diff against.
        frontendEverConnected = true
        lastFrontendConnectedAtMs = nowMs
        if (!coldCaptured && firstLoadStartAt >= 0) {
            coldCaptured = true
            lastLoadStartAt = -1                     // the cold load is consumed — a later bare reconnect (no
            val d = nowMs - firstLoadStartAt         // fresh load) must not diff against the stale launch stamp
            if (d in 1..TTI_MAX_MS) coldTtiMs = d
            return                                   // the cold-closing connect is not also a warm sample
        }
        val start = lastLoadStartAt
        if (start < 0) return                        // no pending fresh load (bare reconnect) → ignore
        lastLoadStartAt = -1
        val d = nowMs - start
        if (d in 1..TTI_MAX_MS) { warmTti.addLast(d); while (warmTti.size > TTI_RING) warmTti.removeFirst() }
    }

    // Involuntary renderer reload/rebuild churn — a proxy for the heap-ceiling/OOM kills that force a
    // reload (the footprint pressure a leaner host relieves). Distinct from the crash-loop budget above
    // (which resets every 60s): this is a monotonic lifetime count plus a 24h rolling window, so `:8888`
    // can show "renderer reloads (24h)". Only INVOLUNTARY reloads count (crash rebuild, handshake-watchdog
    // reload) — an explicit navigate / user reload is not churn. Stamps are elapsed-realtime millis; the
    // ring is pruned on write and on read, and size-capped so a pathological loop can't grow it unbounded.
    private const val RELOAD_WINDOW_MS = 24 * 60 * 60_000L
    private const val RELOAD_STAMP_CAP = 1000
    private val reloadStamps = ArrayDeque<Long>()
    private var reloadsTotal = 0L

    private fun pruneReloads(nowMs: Long) {
        val cutoff = nowMs - RELOAD_WINDOW_MS
        while (reloadStamps.isNotEmpty() && reloadStamps.first() < cutoff) reloadStamps.removeFirst()
    }

    /** Record one INVOLUNTARY renderer reload/rebuild (crash rebuild or handshake-watchdog reload). */
    @Synchronized fun recordRendererReload(nowMs: Long) {
        reloadsTotal++
        reloadStamps.addLast(nowMs)
        pruneReloads(nowMs)
        while (reloadStamps.size > RELOAD_STAMP_CAP) reloadStamps.removeFirst()
    }

    /** Involuntary reloads in the last 24h (prunes first so a long-idle panel doesn't over-report). */
    @Synchronized fun reloads24h(nowMs: Long): Int { pruneReloads(nowMs); return reloadStamps.size }

    /** Responsiveness snapshot for `/api/v1/perf`. -1 fields = not yet captured. Pure (caller supplies now). */
    data class RendererPerf(val coldTtiMs: Long, val warmTtiMedianMs: Long, val reloads24h: Int)

    @Synchronized fun rendererPerf(nowMs: Long): RendererPerf {
        val warmMedian = if (warmTti.isEmpty()) -1L else warmTti.sorted().let { it[it.size / 2] }
        return RendererPerf(coldTtiMs, warmMedian, reloads24h(nowMs))
    }

    /** Clear all responsiveness-measurement state. Real code never needs this (process-global is correct);
     *  it exists so unit tests sharing the JVM can isolate, matching the other reset seams here. */
    @Synchronized fun resetRendererPerf() {
        firstLoadStartAt = -1L; lastLoadStartAt = -1L; coldTtiMs = -1L; coldCaptured = false
        warmTti.clear(); reloadStamps.clear(); reloadsTotal = 0L
        frontendEverConnected = false; lastFrontendConnectedAtMs = -1L
    }

    /**
     * Whether Home Assistant's frontend has ever reported itself connected inside the panel's WebView —
     * setup's evidence that the built-in renderer genuinely reached a dashboard rather than merely being
     * configured to. Process-global and deliberately not persisted: a fresh process must re-earn it, and
     * setup binds it to a configuration fingerprint so a moved broker or revoked token voids it.
     */
    @Volatile var frontendEverConnected = false
        private set

    /** When [frontendEverConnected] was last set, or -1. */
    @Volatile var lastFrontendConnectedAtMs = -1L
        private set

    // Screen-state fan-out to the live renderer. A 24/7 dashboard WebView keeps churning CPU (websocket
    // state, animations, JS timers) behind a dark screen, so when the panel screen goes off/on we tell
    // the renderer to pause/resume the WebView. The activity registers a listener (and must marshal to
    // the UI thread itself); [ScreenController] pokes it. Pure Kotlin so the controller stays testable.
    @Volatile private var screenListener: ((Boolean) -> Unit)? = null

    /** Renderer registers its pause/resume handler here. */
    fun setScreenListener(l: ((Boolean) -> Unit)?) { screenListener = l }

    /** Clear the listener only if it's still [l] — so a destroyed old activity instance can't wipe a
     *  newer instance's registration when their lifecycles overlap. */
    fun clearScreenListener(l: (Boolean) -> Unit) { if (screenListener === l) screenListener = null }

    /** Last screen state pushed by [ScreenController] — read by a renderer CREATED while the panel is
     *  dark (a watchdog/kiosk relaunch at night), which would otherwise assume the screen is on, arm the
     *  handshake watchdog, and never freeze until the next real screen transition. Defaults awake (no
     *  transition seen since process start = panel presumed on). */
    @Volatile var screenAwakeNow = true
        private set

    /** [ScreenController] calls this from sleep()/wake(); no-op when no renderer is listening. */
    fun onScreenAwake(awake: Boolean) { screenAwakeNow = awake; screenListener?.invoke(awake) }

    /** True while the renderer is latched on "HA rejected our login settings" (terminal refresh response /
     *  repeated auth-invalid). Read by the `:8888` health warnings so the failure is visible off-panel;
     *  cleared when a reload/navigate arrives or the frontend connects. */
    @Volatile var authLatched = false
        private set

    // Home Assistant lifecycle fan-out to the live renderer.
    //
    // The poke carries NO state on purpose. A cached copy of the state, its source or a timestamp here
    // would each need its own invalidation rule — a copy is how a recovery notice gets resurrected by a
    // renderer rebuilt after it expired, and how delivery races itself between commit and callback. A
    // payload-free poke cannot go stale and cannot arrive out of order: the renderer reads the one
    // source of truth when it is told something changed.
    //
    // Registration is lock-serialized because activity and service lifetimes overlap: an identity check
    // followed by a clear is two steps, and a successor registering between them would be wiped by its
    // predecessor. The listener is INVOKED outside the lock — it marshals into an activity, and calling
    // foreign code under a lock invites a re-entrant deadlock.
    private val haLifecycleListenerLock = Any()
    private var haLifecycleListener: (() -> Unit)? = null

    /** Renderer registers its "re-read and redraw" handler here (and marshals to the UI thread itself). */
    internal fun setHaLifecycleListener(l: (() -> Unit)?) {
        synchronized(haLifecycleListenerLock) { haLifecycleListener = l }
    }

    /** Clear only if still [l], so a destroyed activity cannot wipe a newer instance's registration. */
    internal fun clearHaLifecycleListener(l: () -> Unit) {
        synchronized(haLifecycleListenerLock) {
            if (haLifecycleListener === l) haLifecycleListener = null
        }
    }

    /**
     * The service calls this when the lifecycle state OR the network-path verdict changed; no-op when
     * no renderer is listening. One poke for both because the renderer re-reads both holders on it,
     * and a second listener would be a second registration, clearing and ordering contract to keep.
     */
    internal fun onHaLifecycleChanged() {
        val listener = synchronized(haLifecycleListenerLock) { haLifecycleListener }
        listener?.invoke()
    }
}
