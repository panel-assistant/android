package io.panelassistant.android

import android.content.Intent
import android.net.Uri
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.platform.AndroidSystemEnv
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.util.LocalAdminEndpoint
import io.panelassistant.android.util.localIpv4
import io.panelassistant.android.util.localIpv6

/**
 * Launcher Activity. Starts [PaneldService], then either opens the configured dashboard under kiosk
 * policy or shows a small standing screen — app icon, the full config URL, and buttons to open the config page or dashboard. The standing screen stays
 * available for explicit admin and recovery entry. The agent runs headless as a foreground service
 * regardless of this Activity.
 */
class MainActivity : AppCompatActivity() {

    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private fun dp(v: Int): Int = PanelTokens.dp(v, resources.displayMetrics.density)
    private val config by lazy { Config(this) }

    /**
     * Where the QR and the Configure button send people. Until this panel has completed setup once, that
     * is the guided setup page — the QR exists to start commissioning, and landing a first-time user on
     * the full settings wall was the original onboarding complaint. A panel that has ever completed goes
     * to Configure as before (and if its setup later re-arms, Configure carries a resume-setup banner, so
     * the guided path stays one tap away rather than this screen re-deriving journey state).
     */
    private fun adminPath(): String = if (config.setupEverCompleted) "/configure" else "/setup"
    private val url: String?
        get() = LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort, adminPath())
    private val handler = Handler(Looper.getMainLooper())

    // ---- live setup stage on the standing screen ----------------------------------------------------
    // The screen used to be identical whether the panel was untouched, mid-commissioning or broken, so
    // someone walking up mid-setup learnt nothing. A loopback poll of the same journey the browser
    // wizard renders fills one bold line + one hint — and, at the sign-in stage, reveals a button. The
    // QR bitmap is re-encoded only when the address or path changes (a synchronous ZXing encode per tick
    // would jank the screen); otherwise only the two TextViews and the button change, and only when the
    // stage actually changes.
    private var stageView: TextView? = null
    private var configButton: Button? = null
    private var configButtonCompact = false
    private var hintView: TextView? = null
    private var signInRow: View? = null
    private var lastStageKey: String? = null
    // The URL the current QR encodes (null: no QR), compared with the live one on each poll tick.
    private var encodedUrl: String? = null
    // The journey most recently applied, kept so a rebuild can restore the presentation immediately
    // rather than waiting for a future poll to report a value that has not changed.
    private var lastJourney: org.json.JSONObject? = null
    private val setupPollExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val setupPoll = object : Runnable {
        override fun run() {
            if (presentedIntro == null) return
            val generation = introGeneration
            setupPollExecutor.execute {
                val body = runCatching {
                    val conn = java.net.URL(
                        LocalAdminEndpoint.loopbackUrl(config.httpPort, "/api/v1/setup"),
                    ).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    try { conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { conn.disconnect() }
                }.getOrNull()
                val live = url
                handler.post {
                    if (generation != introGeneration || presentedIntro == null) return@post
                    if (live != encodedUrl) rebuildIntro()
                    body?.let { runCatching { applySetupStage(org.json.JSONObject(it)) } }
                    handler.postDelayed(this, SETUP_POLL_MS)
                }
            }
        }
    }

    /** The admin button's label, matching where it actually goes. Kept beside [adminPath] so the two
     *  can never drift — the hint text tells the user which button to tap, by name. */
    private fun configButtonLabel(compact: Boolean, complete: Boolean = config.setupEverCompleted): String =
        getString(if (!complete) R.string.set_up else if (compact) R.string.configure else R.string.open_configuration)

    /** Map the journey to the standing screen's one bold line + one hint. The rule: a passer-by always
     *  learns what stage the panel is at AND whether they need to act — "nothing to do here" matters as
     *  much as an instruction, because it is what prevents a second, conflicting editing session. */
    private fun applySetupStage(journey: org.json.JSONObject) {
        val complete = journey.optBoolean("complete", false)
        val next = journey.optString("next", "")
        var rendererDetail = ""
        val steps = journey.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                if (s.optString("stage") == "renderer") rendererDetail = s.optString("detail")
            }
        }
        val needsUser = journey.optBoolean("needs_user", false)
        val key = "$complete|$next|$rendererDetail|$needsUser"
        lastJourney = journey
        if (key == lastStageKey) return
        lastStageKey = key
        val identity = !complete && next == "identity"
        val stage = setupStageLine(complete, next, rendererDetail)?.let { line ->
            getString(line.line) to if (identity) getString(line.hint, configButtonLabel(configButtonCompact, complete = false)) else getString(line.hint)
        }
        statusSurface?.notice?.setPill(standingNoticePill(stage != null, identity, needsUser, config.setupEverCompleted)?.let(::getString).orEmpty())
        // Not set up: the pill says so, and the hint is the one line. A later stage: the stage is the line
        // and its hint the footer. No stage: the panel's description.
        stageView?.text = when {
            stage == null -> getString(R.string.panel_generic_description)
            identity -> stage.second
            else -> stage.first
        }
        hintView?.text = stage?.second.orEmpty()
        hintView?.visibility = if (stage == null || identity) View.GONE else View.VISIBLE
        signInRow?.visibility = if (!complete && next == "ha_credentials") View.VISIBLE else View.GONE
        configButton?.text = configButtonLabel(configButtonCompact, complete)
    }
    // --------------------------------------------------------------------------------------------------

    private var autoReturn: Runnable? = null
    private var autoReturnDeadlineMs = 0L
    private var autoReturnNextAttemptMs = 0L
    private var startupChosen = false
    private var restoredIntroState: SavedLaunchIntroState? = null
    private var introExplicitAdminEntry = false
    private var introVersionPending: Long? = null
    private var introGeneration = 0L
    private var presentedIntro: View? = null
    private var introAcknowledgement: IntroAcknowledgement? = null
    private var preparedAutoReturn: PreparedVisibleAutoReturn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        supportActionBar?.hide()
        setContentView(TextView(this).apply {
            text = getString(applicationInfo.labelRes)
            gravity = android.view.Gravity.CENTER
        })
        restoredIntroState = savedInstanceState?.takeIf { it.getBoolean(STATE_INTRO_PRESENTED, false) }?.let {
            SavedLaunchIntroState(
                explicitAdminEntry = it.getBoolean(STATE_INTRO_EXPLICIT, false),
                pendingVersionCode = it.getLongOrNull(STATE_INTRO_PENDING_VERSION),
                autoReturnRemainingMs = it.getLongOrNull(STATE_AUTO_RETURN_REMAINING),
                autoReturnNextAttemptRemainingMs = it.getLongOrNull(STATE_AUTO_RETURN_NEXT_REMAINING),
            )
        }

        // Config constructs three SQLite-backed namespaces on a cold process. Finish that work before
        // choosing the launch destination. Locale still precedes the service start and launch choice.
        setupPollExecutor.execute {
            config
            // An update can clear preferred HOME. Repair it before requesting the foreground service:
            // if that first service start crashes, Android must still resolve HOME to the dashboard.
            runCatching {
                SystemController(AndroidSystemEnv(this)).applyLauncherHomePolicy(
                    config.launcherPackage,
                    config.dashboardPackage,
                    config.builtInRendererReady(),
                )
            }.onFailure { android.util.Log.w("MainActivity", "launcher HOME policy apply failed", it) }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                NativeLocale.apply(config.uiLanguage)
                // Start while this Activity is visible. On Oreo an ordinary service start avoids
                // arming the foreground deadline before ART has loaded the cold service class.
                // The service still promotes itself at the front of onCreate.
                PaneldService.start(this, fromVisibleActivity = true)
                chooseDestination()
            }
        }
    }

    private fun chooseDestination() {
        if (startupChosen) return
        restoredIntroState?.let { saved ->
            val restored = restoreLaunchIntro(
                saved = saved,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                lastShownVersionCode = config.lastLaunchScreenVersionCode,
                minimumReturnDelayMs = AUTO_RETURN_RESTORE_GRACE_MS,
                maximumReturnWindowMs = AUTO_RETURN_WINDOW_MS,
            )
            restoredIntroState = null
            presentIntro(restored)
            return
        }
        val target = dashboardIntent()
        val explicitAdminEntry = intent?.getBooleanExtra(EXTRA_EXPLICIT_ADMIN_ENTRY, false) == true
        // ACTION_MAIN/CATEGORY_LAUNCHER cannot distinguish a genuine icon tap from Android restoring the
        // launcher task after update/boot. The Admin Launcher tile is the deliberately explicit route;
        // treating every launcher intent as explicit would reopen #31 for routine task restoration.
        // Do not flash the QR screen while waiting for cold service health. A known crash latch is
        // actionable here; otherwise DashboardActivity owns native network/auth recovery and a foreign
        // renderer owns its own startup surface.
        val decision = LaunchScreenPolicy.decide(
            kioskEnabled = config.kioskLock,
            configuredRenderer = config.dashboardPackage,
            builtInUrlConfigured = config.builtInRendererReady(),
            dashboardLaunchAvailable = target != null,
            dashboardRecoveryBlocked = dashboardRecoveryState() != PanelStatus.DashboardRecoveryState.NONE,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            lastShownVersionCode = config.lastLaunchScreenVersionCode,
            explicitAdminEntry = explicitAdminEntry,
        )
        if (decision.destination == LaunchDestination.DASHBOARD && target != null) {
            val launched = runCatching { startActivity(target) }.isSuccess
            if (launched) {
                startupChosen = true
                finish()
                return
            }
        }

        presentIntro(
            RestoredLaunchIntroPlan(
                explicitAdminEntry = explicitAdminEntry,
                pendingVersionCode = BuildConfig.VERSION_CODE.toLong().takeIf {
                    decision.rememberVersionShown
                },
                autoReturnRemainingMs = null,
                autoReturnDelayMs = null,
            ),
            freshDecision = decision,
        )
    }

    private fun presentIntro(
        plan: RestoredLaunchIntroPlan,
        freshDecision: LaunchScreenDecision? = null,
    ) {
        // Install the recovery/config surface before acknowledging it. A failed durable commit simply
        // shows the intro again next time, which is safer than hiding the only visible recovery route.
        val intro = buildUi()
        setContentView(intro)
        presentedIntro = intro
        updateKioskAdminVisibility()
        introExplicitAdminEntry = plan.explicitAdminEntry
        introVersionPending = plan.pendingVersionCode
        startupChosen = true
        preparedAutoReturn = if (!plan.explicitAdminEntry) {
            if (plan.autoReturnRemainingMs != null && plan.autoReturnDelayMs != null) {
                prepareAutoReturn(
                    restoredRemainingMs = plan.autoReturnRemainingMs,
                    restoredDelayMs = plan.autoReturnDelayMs,
                )
            } else if (freshDecision != null) {
                prepareAutoReturn(ignoreUpdateAge = freshDecision.rememberVersionShown)
            } else null
        } else null
        acknowledgeIntroAfterFirstDraw(intro, plan.pendingVersionCode)
        // Start the stage poll only once the intro is the content view; it self-stops when the intro
        // generation moves on or the activity is destroyed.
        lastStageKey = null
        handler.removeCallbacks(setupPoll)
        handler.post(setupPoll)
    }

    private fun acknowledgeIntroAfterFirstDraw(view: View, versionCode: Long?) {
        introAcknowledgement?.cancel()
        val generation = ++introGeneration
        introAcknowledgement = IntroAcknowledgement(view, versionCode, generation).also { it.arm() }
    }

    private inner class IntroAcknowledgement(
        private val view: View,
        private val versionCode: Long?,
        private val generation: Long,
    ) : ViewTreeObserver.OnDrawListener, Runnable {
        private val observer = view.viewTreeObserver
        private var posted = false

        fun arm() = observer.addOnDrawListener(this)

        override fun onDraw() {
            if (posted) return
            posted = true
            // OnDrawListener cannot be removed during dispatch. Posting completes this draw first.
            view.post(this)
        }

        override fun run() {
            removeListener()
            if (introAcknowledgement === this) introAcknowledgement = null
            if (currentPresentation()) {
                // Start the redirect clock only after Android has completed the first QR-screen draw.
                // Slow startup and rendering must not consume the visible eight-second dwell time.
                preparedAutoReturn?.let {
                    preparedAutoReturn = null
                    armAutoReturn(it)
                }
                versionCode?.let { pendingVersion ->
                    setupPollExecutor.execute {
                        val committed = config.commitLaunchScreenVersionShown(pendingVersion)
                        handler.post {
                            if (committed && introVersionPending == pendingVersion) {
                                introVersionPending = null
                            }
                        }
                    }
                }
            }
        }

        private fun currentPresentation(): Boolean = mayAcknowledgePresentedIntro(
            generationMatches = generation == introGeneration,
            presentedViewMatches = presentedIntro === view,
            viewAttached = view.isAttachedToWindow,
            activityFinishing = isFinishing,
            activityDestroyed = isDestroyed,
            startupChosen = startupChosen,
        )

        fun cancel() {
            view.removeCallbacks(this)
            removeListener()
        }

        private fun removeListener() {
            if (observer.isAlive) observer.removeOnDrawListener(this)
        }
    }

    // After an app update the launcher lands on this UI; once the configured renderer is launchable,
    // bounce back to the dashboard so it doesn't linger. Cancelled by any
    // touch (so someone who opened it on purpose isn't yanked away). Gated on a recent app update, so a
    // deliberate open long afterwards just stays put. A policy-selected changed-version intro also gets
    // this short return even when kiosk was enabled after the APK update timestamp aged out.
    //
    // POLL rather than check once: after a restart (worst case a whole-fleet restart flooding Home Assistant)
    // The built-in renderer is ready once its HA URL and launch target exist. A launchable external
    // renderer owns its own HA readiness. Re-check every
    // [AUTO_RETURN_POLL_MS] until the renderer-specific gate opens or the [AUTO_RETURN_WINDOW_MS]
    // window elapses.
    private fun prepareAutoReturn(
        ignoreUpdateAge: Boolean = false,
        restoredRemainingMs: Long? = null,
        restoredDelayMs: Long? = null,
    ): PreparedVisibleAutoReturn? {
        if (dashboardIntent() == null) return null
        val updated = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (restoredRemainingMs == null &&
            !ignoreUpdateAge &&
            System.currentTimeMillis() - updated > 5 * 60 * 1000L
        ) return null
        return prepareVisibleAutoReturn(
            restoredRemainingMs = restoredRemainingMs,
            restoredDelayMs = restoredDelayMs,
            defaultWindowMs = AUTO_RETURN_WINDOW_MS,
            defaultFirstDelayMs = AUTO_RETURN_FIRST_MS,
        )
    }

    private fun armAutoReturn(prepared: PreparedVisibleAutoReturn) {
        val now = SystemClock.elapsedRealtime()
        val schedule = armVisibleAutoReturn(prepared, now)
        val r = object : Runnable {
            override fun run() {
                val launchAvailable = dashboardIntent() != null
                if (PostUpdateReturnPolicy.dashboardReady(
                        configuredRenderer = config.dashboardPackage,
                        builtInUrlConfigured = config.builtInRendererReady(),
                        dashboardLaunchAvailable = launchAvailable,
                        dashboardRecoveryBlocked = dashboardRecoveryState() != PanelStatus.DashboardRecoveryState.NONE,
                    )
                ) {
                    val opened = openDashboard()
                    if (opened || RendererResolver.companionHomeRoute(
                            config.dashboardPackage, config.homeDashboard,
                        ) == null) {
                        cancelAutoReturn()
                        if (opened) finish()
                        return
                    }
                }
                val retryNow = SystemClock.elapsedRealtime()
                if (retryNow >= schedule.deadlineMs) { cancelAutoReturn(); return } // give up (unconfigured)
                autoReturnNextAttemptMs = retryNow + AUTO_RETURN_POLL_MS
                handler.postDelayed(this, AUTO_RETURN_POLL_MS)
            }
        }
        autoReturn = r
        autoReturnDeadlineMs = schedule.deadlineMs
        autoReturnNextAttemptMs = schedule.firstAttemptMs
        handler.postDelayed(r, prepared.firstDelayMs) // let a genuine touch cancel + give the network a head start
    }

    private fun cancelAutoReturn() {
        preparedAutoReturn = null
        autoReturn?.let { handler.removeCallbacks(it) }
        autoReturn = null
        autoReturnDeadlineMs = 0L
        autoReturnNextAttemptMs = 0L
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) cancelAutoReturn() // user is here on purpose
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (presentedIntro != null) {
            outState.putBoolean(STATE_INTRO_PRESENTED, true)
            outState.putBoolean(STATE_INTRO_EXPLICIT, introExplicitAdminEntry)
            introVersionPending?.let { outState.putLong(STATE_INTRO_PENDING_VERSION, it) }
            if (autoReturn != null) {
                val now = SystemClock.elapsedRealtime()
                val remaining = (autoReturnDeadlineMs - now).coerceIn(0L, AUTO_RETURN_WINDOW_MS)
                val nextRemaining = (autoReturnNextAttemptMs - now).coerceIn(0L, remaining)
                outState.putLong(STATE_AUTO_RETURN_REMAINING, remaining)
                outState.putLong(STATE_AUTO_RETURN_NEXT_REMAINING, nextRemaining)
            } else preparedAutoReturn?.let { pending ->
                // Recreation before the first frame keeps the full not-yet-visible dwell time.
                outState.putLong(STATE_AUTO_RETURN_REMAINING, pending.remainingMs)
                outState.putLong(STATE_AUTO_RETURN_NEXT_REMAINING, pending.firstDelayMs)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        KioskAdminUi.setVisible(this, false)
        cancelAutoReturn()
        handler.removeCallbacks(setupPoll)
        // A first-draw acknowledgement may be queued behind a setup poll. Let that durable
        // SQLite commit finish after the Activity leaves; no new polls are admitted below.
        setupPollExecutor.shutdown()
        introAcknowledgement?.cancel()
        introAcknowledgement = null
        presentedIntro = null
        configButton = null
        stageView = null
        hintView = null
        signInRow = null
        introGeneration++
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (maintenanceFence.stop(this)) return
        // Returning from the dashboard or the config page can arrive after the panel's theme changed,
        // and recomputing the palette only helps views built AFTER that point — the installed screen
        // kept the colours it was born with. Rebuild only when the theme actually moved, so an
        // unchanged return does not reattach the hierarchy for nothing.
        presentedIntro?.let {
            val dark = StatusSurface.darkFor(this, config)
            if (statusSurface?.dark != dark) rebuildIntro()
        }
        updateKioskAdminVisibility()
    }

    /** Rebuild the standing screen in place: after a theme change, or when the QR's address or path moved. */
    private fun rebuildIntro() {
        statusSurface = null
        val refreshed = buildUi()
        setContentView(refreshed)
        presentedIntro = refreshed
        // The rebuilt views are blank until the next poll, and the poll returns early while the
        // journey key is unchanged — so at `ha_credentials` the stage stayed hidden, the generic
        // hint stayed, and the sign-in button could disappear until the journey happened to move.
        // A rebuild forgets what it last applied and reapplies the state it already has.
        lastStageKey = null
        lastJourney?.let { journey -> runCatching { applySetupStage(journey) } }
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    private fun updateKioskAdminVisibility() {
        KioskAdminUi.setVisible(this, presentedIntro != null)
    }

    private fun buildUi(): View {
        val surface = statusSurface()
        val notice = surface.notice
        // The QR leads; no address is printed, setup is continued from Panel Assistant.
        val u = url
        encodedUrl = u
        val qr = u?.let { notice.qr(it, getString(R.string.config_qr_description, it), getString(R.string.notice_qr_set_up)) }
        // The line: the live setup stage, the answer to someone walking up to the panel mid-commissioning —
        // what is it doing, and do I need to act? Filled by the loopback journey poll; until it reports, the
        // panel's description.
        val line = notice.line(getString(R.string.panel_generic_description))
        stageView = line
        val hint = notice.footer("").apply { visibility = View.GONE }
        hintView = hint
        // The label follows the journey for the same reason the URL does — on an unfinished panel this opens
        // guided setup, so calling it "configuration" both undersells it and contradicts the hint, which
        // tells the user which button to tap. Updated live by applySetupStage.
        val cfgBtn = notice.action(configButtonLabel(compact = true), primary = true) { openConfig() }
        configButton = cfgBtn
        configButtonCompact = true
        val recovery = dashboardRecoveryState()
        val dashboardLabel = if (recovery == PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER) {
            getString(R.string.retry_dashboard)
        } else {
            getString(R.string.dashboard)
        }
        val haBtn = dashboardIntent()?.let { notice.action(dashboardLabel) { openDashboard() } }
        // The one moment setup hands BACK to the panel: sign-in. The wizard announces it in the browser;
        // this button is the same action under the user's finger, so the handoff never depends on the
        // automatic relaunch having fired. DashboardActivity's readiness gate routes a URL-without-
        // credentials start to the on-panel Home Assistant login rather than bouncing here.
        val signIn = notice.actions(notice.action(getString(R.string.sign_in_home_assistant)) {
            runCatching { startActivity(builtinDashboardIntent()) }
        }).apply { visibility = View.GONE }
        signInRow = signIn
        surface.setBody(
            standingNoticePill(stagePresent = false, identity = false, needsUser = false, everCompleted = config.setupEverCompleted)
                ?.let(::getString).orEmpty(),
            qr,
            line,
            notice.actions(*listOfNotNull(cfgBtn, haBtn).toTypedArray()),
            signIn,
            hint,
        )
        return surface.root
    }

    /**
     * The frame, kept across rebuilds and rebuilt only when the theme or the panel geometry changes.
     * Shared with the dashboard's status screens so the standing screen cannot drift into a second mark,
     * a second palette or a second theme rule.
     */
    private var statusSurface: StatusSurface? = null

    private fun statusSurface(): StatusSurface {
        val dark = StatusSurface.darkFor(this, config)
        val geometry = StatusSurface.geometryFor(this)
        statusSurface?.let { if (statusSurfaceReusable(it.geometry, it.dark, geometry, dark)) return it }
        return StatusSurface(this, dark).also { statusSurface = it }
    }

    // Open the config page in-app — kiosk panels usually have no browser, so an ACTION_VIEW intent
    // would find no handler and do nothing.
    private fun openConfig() {
        runCatching {
            startActivity(Intent(this, ConfigActivity::class.java).putExtra("path", adminPath()))
        }
    }

    /** Intent that opens the configured dashboard: a ready automatic/explicit built-in renderer or an
     * explicitly selected foreign renderer. Null preserves the recovery screen when nothing is ready. */
    private fun dashboardIntent(): Intent? {
        return when (val target = RendererResolver.resolveLaunchable(
            configuredPackage = config.dashboardPackage,
            builtinReady = config.builtInRendererReady(),
            isLaunchable = { packageManager.getLaunchIntentForPackage(it) != null },
        )) {
            RendererTarget.Builtin -> builtinDashboardIntent()
            is RendererTarget.Foreign -> {
                val home = RendererResolver.companionHomeRoute(target.packageName, config.homeDashboard)
                val route = home?.let {
                    Intent(Intent.ACTION_VIEW, Uri.parse(NavigateController.homeUrl(it)))
                        .setPackage(target.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                route?.takeIf { it.resolveActivity(packageManager) != null }
                    ?: packageManager.getLaunchIntentForPackage(target.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            null -> null
        }
    }


    private fun dashboardRecoveryState(): PanelStatus.DashboardRecoveryState =
        PanelStatus.dashboardRecoveryState(
            config.dashboardPackage,
            packageName,
            SystemClock.elapsedRealtime(),
        )

    private fun openDashboard(): Boolean {
        if (dashboardRecoveryState() == PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER) {
            BuiltinDashboard.requestExplicitReload()
        }
        return dashboardIntent()?.let { runCatching { startActivity(it) }.isSuccess } ?: false
    }

    private fun Bundle.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    companion object {
        internal const val EXTRA_EXPLICIT_ADMIN_ENTRY =
            "io.panelassistant.android.extra.EXPLICIT_ADMIN_ENTRY"
        private const val STATE_INTRO_PRESENTED = "launch_intro_presented"
        private const val STATE_INTRO_EXPLICIT = "launch_intro_explicit"
        private const val STATE_INTRO_PENDING_VERSION = "launch_intro_pending_version"
        private const val STATE_AUTO_RETURN_REMAINING = "launch_intro_return_remaining"
        private const val STATE_AUTO_RETURN_NEXT_REMAINING = "launch_intro_return_next_remaining"
        private const val AUTO_RETURN_RESTORE_GRACE_MS = 250L
        const val AUTO_RETURN_FIRST_MS = 8_000L   // initial delay before the first redirect attempt
        const val AUTO_RETURN_POLL_MS = 2_000L    // re-check cadence while waiting for the network to reconnect
        const val AUTO_RETURN_WINDOW_MS = 90_000L // give up after this (genuinely unconfigured panel)
        const val SETUP_POLL_MS = 3_000L          // standing-screen stage refresh (loopback, in-memory read)
    }
}
