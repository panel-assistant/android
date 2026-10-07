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
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val config by lazy { Config(this) }

    /**
     * Where the QR and the Configure button send people. Until this panel has completed setup once, that
     * is the guided setup page — the QR exists to start commissioning, and landing a first-time user on
     * the full settings wall was the original onboarding complaint. A panel that has ever completed goes
     * to Configure as before (and if its setup later re-arms, Configure carries a resume-setup banner, so
     * the guided path stays one tap away rather than this screen re-deriving journey state).
     */
    private fun adminPath(): String = if (config.setupEverCompleted) "/configure" else "/setup"
    private val url: String
        get() = LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort, adminPath())
    private val handler = Handler(Looper.getMainLooper())

    // ---- live setup stage on the standing screen ----------------------------------------------------
    // The screen used to be identical whether the panel was untouched, mid-commissioning or broken, so
    // someone walking up mid-setup learnt nothing. A loopback poll of the same journey the browser
    // wizard renders fills one bold line + one hint — and, at the sign-in stage, reveals a button. The
    // QR bitmap is never regenerated (a synchronous ZXing encode per tick would jank the screen); only
    // the two TextViews and the button change, and only when the stage actually changes.
    private var stageView: TextView? = null
    private var configButton: Button? = null
    private var configButtonCompact = false
    private var hintView: TextView? = null
    private var signInButton: Button? = null
    private var lastStageKey: String? = null
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
                handler.post {
                    if (generation != introGeneration || presentedIntro == null) return@post
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
        var connectionDetail = ""
        var rendererDetail = ""
        val steps = journey.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                when (s.optString("stage")) {
                    "mqtt_connection" -> connectionDetail = s.optString("detail")
                    "renderer" -> rendererDetail = s.optString("detail")
                }
            }
        }
        val key = "$complete|$next|$connectionDetail|$rendererDetail"
        lastJourney = journey
        if (key == lastStageKey) return
        lastStageKey = key
        val stage: Pair<String, String>? = when {
            complete -> null // the launcher's own auto-return takes over; show the pre-wizard screen
            next == "identity" -> getString(R.string.panel_not_set_up) to
                getString(R.string.panel_not_set_up_hint, configButtonLabel(configButtonCompact, complete = false))
            next == "mqtt_broker" || next == "mqtt_credentials" ->
                if (connectionDetail == "auth_failed") {
                    getString(R.string.mqtt_could_not_connect) to getString(R.string.mqtt_credentials_rejected_hint)
                } else {
                    getString(R.string.waiting_for_mqtt_details) to getString(R.string.someone_configuring_hint)
                }
            next == "mqtt_connection" ->
                if (connectionDetail == "unreachable" || connectionDetail == "config_error") {
                    getString(R.string.mqtt_could_not_connect) to getString(R.string.mqtt_address_check_hint)
                } else {
                    getString(R.string.checking_mqtt_connection) to getString(R.string.takes_few_seconds_hint)
                }
            // A too-old engine is the one renderer problem a passer-by cannot act on here, so name it as the
            // panel's own fault rather than asking for a choice that would not help.
            next == "renderer" && rendererDetail.startsWith("webview_too_old") ->
                getString(R.string.browser_engine_too_old) to getString(R.string.browser_engine_too_old_hint)
            next == "renderer" -> getString(R.string.choose_dashboard_app) to getString(R.string.continue_setup_browser)
            next == "ha_url" -> getString(R.string.connected_next_ha_address) to getString(R.string.continue_setup_browser)
            next == "ha_credentials" -> getString(R.string.almost_there_sign_in) to getString(R.string.continue_or_sign_in_here)
            // The panel is deliberately holding its first load here, so it must say that rather than look
            // stalled — and say the wait ends in a browser, since nothing on this screen can end it.
            next == "entity_filter" -> getString(R.string.one_important_question_left) to getString(R.string.finish_browser_optimize)
            next == "render_proof" -> getString(R.string.loading_dashboard) to getString(R.string.nothing_to_do_here)
            else -> null
        }
        stageView?.visibility = if (stage == null) View.GONE else View.VISIBLE
        stageView?.text = stage?.first ?: ""
        hintView?.text = stage?.second ?: getString(R.string.panel_generic_description)
        signInButton?.visibility = if (!complete && next == "ha_credentials") View.VISIBLE else View.GONE
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

    /** Colours for the standing screen, shared with every other ha-paneld status surface. The wordmark
     *  itself switches via res/drawable(-night)-nodpi/wordmark.png; [statusPalette] covers the rest.
     *
     *  The theme decision is [statusSurfaceDark] rather than this screen's former system-only reading:
     *  a panel with a configured dashboard theme used to get a light standing screen and a dark
     *  startup screen minutes apart, which is one panel appearing to be two applications. */
    // Recomputed on every read, never cached. Held in a `by lazy` this outlived a configuration
    // change: the shared frame rebuilt itself with the new theme while the body kept the colours of
    // the old one, so one screen was drawn from two themes at once.
    private val pal: StatusPalette get() = statusPalette(StatusSurface.darkFor(this, config))

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
    // POLL rather than check once: after a restart (worst case a whole-fleet restart flooding the broker)
    // The built-in renderer is ready once its HA URL and launch target exist. A launchable external
    // renderer owns its own HA readiness; MQTT is optional for both. Re-check every
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
        handler.postDelayed(r, prepared.firstDelayMs) // let a genuine touch cancel + give MQTT a head start
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
        signInButton = null
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
            if (statusSurface?.dark != dark) {
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
        }
        updateKioskAdminVisibility()
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    private fun updateKioskAdminVisibility() {
        KioskAdminUi.setVisible(this, presentedIntro != null)
    }

    private fun buildUi(): View {
        val dm = resources.displayMetrics
        val hDp = (dm.heightPixels / dm.density).toInt()
        // Scale to the vertical budget so it fits WITHOUT scrolling on a 480x480 panel, yet the icon
        // and QR grow prominent on roomy displays. Tiers: tight (≈480²) / medium / large.
        val compact = hDp < 560
        val qrDp = when { hDp < 560 -> 132; hDp < 900 -> 192; else -> 240 }
        val pad = if (compact) dp(16) else dp(36)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // CENTER (not just CENTER_HORIZONTAL): with the ScrollView's fillViewport=true the column is
            // stretched to the viewport height, so this vertically centres the content in any spare
            // space; when content exceeds the screen it keeps its natural height and the ScrollView scrolls.
            gravity = Gravity.CENTER
            // No vertical padding of its own: the shared frame already insets the body, and this
            // screen has to keep fitting a 480x480 panel without scrolling, so the two must not stack.
            setPadding(dp(24), 0, dp(24), 0)
        }
        // The mark is NOT in this column. It used to be its first child, which meant every live update
        // to the status line below re-centred the column and moved the mark with it. It now sits in the
        // shared fixed band above, built from the one theme value this screen also picks its colours
        // from — the two used to disagree, so a panel configured opposite to the system drew the mark's
        // dark ink onto a dark background.
        // Live setup stage — the answer to someone walking up to the panel mid-commissioning: what is it
        // doing, and do I need to act? Filled by the loopback journey poll; hidden until data arrives so
        // an unpolled screen is exactly the pre-wizard layout.
        stageView = text("", if (compact) 14f else 16f, pal.body, bold = true, padBottom = if (compact) 4 else 8)
            .also { it.visibility = View.GONE; root.addView(it) }
        // Description — shown on all panels (the shorter wordmark frees the vertical space on 480x480);
        // slightly smaller + tighter on compact so it still fits without scrolling. Doubles as the
        // stage HINT once the journey poll reports (the generic text returns when setup completes).
        hintView = text(
            getString(R.string.panel_generic_description),
            if (compact) 12.5f else 14f, pal.body, padBottom = if (compact) 10 else 22,
        ).also { root.addView(it) }
        // The full URL — tappable here, and readable so it can be typed on another device.
        root.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 15f else 18f
            setTextColor(Color.parseColor(pal.accent))
            text = url
            setOnClickListener { openConfig() }
            setPadding(0, 0, 0, dp(if (compact) 6 else 0))
        })
        if (!compact) root.addView(text(getString(R.string.open_address_browser), 12f, pal.subtle, padTop = 4, padBottom = 16))
        // QR of the config URL — scan with a phone instead of typing it.
        qrBitmap(url, dp(qrDp))?.let { qr ->
            root.addView(ImageView(this).apply {
                setImageBitmap(qr)
                contentDescription = getString(R.string.config_qr_description, url)
                layoutParams = LinearLayout.LayoutParams(dp(qrDp), dp(qrDp)).apply { topMargin = dp(6); bottomMargin = dp(4) }
            })
            if (!compact) root.addView(text(getString(R.string.scan_config_phone), 12f, pal.subtle, padBottom = 24))
        }
        // Buttons: side-by-side when vertical space is tight (shorter labels), stacked otherwise.
        // The label follows the journey for the same reason the URL does — on an unfinished panel this
        // opens guided setup, so calling it "configuration" both undersells it and contradicts the hint
        // above, which tells the user which button to tap. Updated live by applySetupStage.
        val cfgBtn = button(configButtonLabel(compact)) { openConfig() }
        configButton = cfgBtn
        configButtonCompact = compact
        val recovery = dashboardRecoveryState()
        val dashboardLabel = if (recovery == PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER) {
            getString(R.string.retry_dashboard)
        } else if (compact) {
            getString(R.string.dashboard)
        } else {
            getString(R.string.open_dashboard)
        }
        val haBtn = dashboardIntent()?.let { button(dashboardLabel) { openDashboard() } }
        if (compact && haBtn != null) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                fun weighted(b: Button) { b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(10); marginStart = dp(4); marginEnd = dp(4) } }
                weighted(cfgBtn); weighted(haBtn)
                addView(cfgBtn); addView(haBtn)
            })
        } else {
            root.addView(cfgBtn)
            haBtn?.let { root.addView(it) }
        }
        // The one moment setup hands BACK to the panel: sign-in. The wizard announces it in the browser;
        // this button is the same action under the user's finger, so the handoff never depends on the
        // automatic relaunch having fired. DashboardActivity's readiness gate routes a URL-without-
        // credentials start to the on-panel Home Assistant login rather than bouncing here.
        signInButton = button(getString(R.string.sign_in_home_assistant)) {
            runCatching {
                startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.also { it.visibility = View.GONE; root.addView(it) }

        // The shared frame supplies the fixed brand band, the background, the capped and centred
        // reading column and the scroll safety net. This column goes in as ONE row so the frame adds
        // no inter-row spacing of its own — the spacing here is the tuned first-run layout, not a
        // status phase, and it has to keep fitting a 480x480 panel without scrolling.
        val surface = statusSurface()
        // The build number belongs on this screen — it is the one a bug report quotes.
        surface.setBody(root)
        return surface.root
    }

    /**
     * The branded frame, kept across rebuilds and rebuilt only when the theme or the panel geometry
     * changes. Shared with the dashboard's status screens so the standing screen cannot drift into a
     * second mark, a second palette or a second theme rule.
     */
    private var statusSurface: StatusSurface? = null

    private fun statusSurface(): StatusSurface {
        val dark = StatusSurface.darkFor(this, config)
        val spec = StatusSurface.specFor(this)
        statusSurface?.let { if (statusSurfaceReusable(it.spec, it.dark, spec, dark)) return it }
        return StatusSurface(this, dark).also { statusSurface = it }
    }

    private fun text(
        s: String, size: Float, color: String,
        bold: Boolean = false, padTop: Int = 0, padBottom: Int = 0, maxWidth: Int = 0,
    ): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (maxWidth > 0) this.maxWidth = dp(maxWidth)
        setPadding(0, dp(padTop), 0, dp(padBottom))
        text = s
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        // The shape, padding and label size are shared with the status screens' actions rather than
        // spelled out here. They were spelled out here, and the status screens quietly grew a
        // different control: no padding at all, an upper-cased label and no separation between a pair.
        // Naming the same constants is what stops that happening again; the drawn result is unchanged.
        text = label
        isAllCaps = false
        textSize = STATUS_ACTION_LABEL_SP
        setTextColor(Color.parseColor(pal.actionText))
        background = GradientDrawable().apply {
            cornerRadius = dp(STATUS_ACTION_CORNER_DP).toFloat()
            setColor(Color.parseColor(pal.actionBackground))
        }
        setPadding(
            dp(STATUS_ACTION_PADDING_H_DP), dp(STATUS_ACTION_PADDING_V_DP),
            dp(STATUS_ACTION_PADDING_H_DP), dp(STATUS_ACTION_PADDING_V_DP),
        )
        layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8); bottomMargin = dp(8) }
        setOnClickListener { onClick() }
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
            RendererTarget.Builtin -> Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        const val AUTO_RETURN_POLL_MS = 2_000L    // re-check cadence while waiting for MQTT to reconnect
        const val AUTO_RETURN_WINDOW_MS = 90_000L // give up after this (genuinely unconfigured panel)
        const val SETUP_POLL_MS = 3_000L          // standing-screen stage refresh (loopback, in-memory read)
    }
}
