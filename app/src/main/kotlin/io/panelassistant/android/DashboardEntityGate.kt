package io.panelassistant.android

import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.EntityBootstrapProblem
import io.panelassistant.android.dashboard.EntityFilterProtocol
import io.panelassistant.android.dashboard.EntityFilterTelemetry
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.dashboard.RendererEntityHold
import io.panelassistant.android.dashboard.entityLearningHold
import io.panelassistant.android.dashboard.shouldHoldRendererForEntityBootstrap
import io.panelassistant.android.dashboard.shouldInstallDashboardTrafficObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class EntityFilterPreparationInput(
    val enabled: Boolean,
    val learningEnabled: Boolean,
    val ids: List<String>,
    val haUrl: String,
    val origins: Set<String>,
)

internal data class PreparedEntityFilter(
    val signature: String,
    val ids: List<String> = emptyList(),
    val hash: String = "",
    val script: Result<String>? = null,
)

/** The same preparation used by cold HOME startup and subsequent renderer rebuilds. */
internal suspend fun prepareEntityFilterOffMain(
    read: () -> EntityFilterPreparationInput,
    hash: (Iterable<String>) -> String = EntityFilterProtocol::hash,
): PreparedEntityFilter = withContext(Dispatchers.IO) {
    val input = read()
    val learning = ":learning=${input.learningEnabled}"
    if (!input.enabled) {
        PreparedEntityFilter("disabled$learning")
    } else {
        runCatching {
            val ids = EntityFilterProtocol.normalize(input.ids)
            val digest = hash(ids)
            PreparedEntityFilter(
                signature = "enabled:$digest:${input.haUrl}$learning",
                ids = ids,
                hash = digest,
                script = runCatching {
                    EntityFilterProtocol.documentStartScript(input.haUrl, ids, input.origins)
                },
            )
        }.getOrDefault(PreparedEntityFilter("invalid$learning"))
    }
}

internal enum class EntityFilterFailureDisposition { HOLD_NATIVE, ALLOW_DIRECT }

/** Automatic learning turns an interceptor failure into a native hold rather than a full, unfiltered HA
 *  stream; the hold's time limit ([RendererEntityHold]) decides when the dashboard opens unfiltered anyway. */
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

internal data class EntityFilterNativeHold(val error: String, val detail: String)

internal class EntityFilterInterceptorUnavailable(cause: Throwable) : RuntimeException(cause)

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

/** The clock and timers an entity hold runs on: the activity's Handler, or a JVM test's virtual clock. */
internal interface EntityHoldTimers {
    fun now(): Long
    fun post(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

private class HandlerEntityHoldTimers(private val main: Handler) : EntityHoldTimers {
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun post(task: Runnable, delayMs: Long) { main.postDelayed(task, delayMs) }
    override fun cancel(task: Runnable) = main.removeCallbacks(task)
}

/** What an entity hold reads from the service-owned learner; JVM tests supply each kind's state. */
internal interface EntityHoldLearner {
    fun blockingIssueCount(): Int = EntityLearningRuntime.blockingIssueCount()
    fun bootstrapProblem(): EntityBootstrapProblem? = EntityLearningRuntime.bootstrapProblem()
    fun canIgnoreBlockingIssues(): Boolean = EntityLearningRuntime.canIgnoreBlockingIssues()
    fun bootstrapMilestoneState(): EntityLearningRuntime.BootstrapMilestone? = EntityLearningRuntime.bootstrapMilestoneState()
    fun syncRunning(): Boolean = EntityLearningRuntime.syncRunning()
    fun retryBootstrap(): Boolean = EntityLearningRuntime.retryBootstrap()
    fun probeDashboardChange(): Boolean = EntityLearningRuntime.probeDashboardChange()
}

private object RuntimeEntityHoldLearner : EntityHoldLearner

/** Paints one entity hold's screen, returning the first scan's live views when it shows them. */
internal fun interface EntityHoldPainter {
    fun paint(kind: EntityHoldKind, blockingIssues: Int, filterHold: EntityFilterNativeHold?): EntityLearningViews?
}

/**
 * The entity-filter gate in front of the dashboard WebView: it prepares the document-start allow-list,
 * decides whether automatic filtering must hold the renderer behind a native screen, keeps that hold and
 * its retry and bootstrap poll, and installs the interceptor on the WebView once the renderer may open.
 * Every hold is time-limited ([RendererEntityHold]): past its bound the dashboard opens unfiltered while
 * learning keeps retrying, and is rebuilt filtered once the cause clears.
 * [DashboardActivity] decides when to build and calls back here; every timer posts on the activity's own
 * [main] Handler, so the activity's `removeCallbacksAndMessages(null)` at destroy still clears all of them.
 */
internal class DashboardEntityGate(
    private val activity: AppCompatActivity,
    private val main: Handler,
    private val scope: CoroutineScope,
    private val statusScreens: DashboardStatusScreens,
    private val host: Host,
    /** How the gate reads settings; JVM tests pass one over in-memory preferences. */
    private val readConfig: () -> Config = { Config(activity) },
    /** The clock and timers: [main] and the elapsed-realtime clock; JVM tests run a virtual clock. */
    private val timers: EntityHoldTimers = HandlerEntityHoldTimers(main),
    /** The learner's state; JVM tests supply each hold kind's. */
    private val learner: EntityHoldLearner = RuntimeEntityHoldLearner,
    /** How a hold screen is painted; JVM tests record the kind instead. */
    holdPainter: EntityHoldPainter? = null,
    /** Whether this panel's web viewer runs document-start scripts; JVM tests choose. */
    private val documentStartSupported: () -> Boolean = {
        webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
    },
    /** Opens the panel's own settings at a path; JVM tests record the path instead. */
    private val openSettings: (String) -> Unit = { path ->
        activity.startActivity(Intent(activity, ConfigActivity::class.java).putExtra("path", path))
    },
    /** How the entity-filter question's waiting screen is painted, given its actions; JVM tests record them. */
    questionPainter: ((List<Pair<Int, () -> Unit>>) -> Unit)? = null,
) {
    interface Host {
        val activityOwner: Long
        val destroyed: Boolean
        val screenAwake: Boolean
        fun buildAndLoad(config: Config)
        fun teardownWeb()
        /** Reconcile the live renderer with the settings, rebuilding what no longer matches. */
        fun reconcile()
    }

    private val painter = holdPainter ?: EntityHoldPainter(::paintHoldScreen)
    private val paintQuestion = questionPainter ?: ::paintEntityFilterQuestion

    var entityFilterSignature = "disabled"
        private set
    private var preparedEntityFilter: PreparedEntityFilter? = null

    /** The signature of the preparation the gate was last configured from. [entityFilterSignature] can
     *  differ from it: configuring a filter that cannot be installed records the renderer as unfiltered. */
    val preparedSignature: String? get() = preparedEntityFilter?.signature
    var entityFilterLease: EntityFilterTelemetry.Lease? = null
        private set
    private var entityFilterNativeHold: EntityFilterNativeHold? = null
    // A relaunch or retry that begins while a large filter is being prepared supersedes that result.
    var filterPreparationEpoch = 0L
        private set
    private val entityFilterRetryPolicy = EntityFilterRetryPolicy()
    /** When the pending entity-filter retry is due, so a redraw reschedules it rather than restarting it. */
    private var entityFilterRetryDueAtMs = 0L
    private var entityBootstrapBlockedCount = -1
    /** A dashboard WebView was admitted after the last hold screen: a superseded poll has nothing to wait for. */
    private var rendererAdmitted = false
    /** The hold kind on screen, until a renderer is admitted; what the time limit releases. */
    private var holdShown: EntityHoldKind? = null
    private var entityBootstrapMilestoneView: TextView? = null
    private var entityLearningLine: TextView? = null
    private var entityLearningTrack: NoticeTrack? = null
    /** Owns the hold's retry clocks: the resync ladder and the decision-hold probe cadence. */
    private val entityBootstrapWatchdog = EntityBootstrapWatchdog(
        retryMs = BOOTSTRAP_WATCHDOG_RETRY_MS,
        retryCeilingMs = BOOTSTRAP_WATCHDOG_RETRY_CEILING_MS,
        probeMs = BOOTSTRAP_WATCHDOG_PROBE_MS,
        probeCeilingMs = BOOTSTRAP_WATCHDOG_PROBE_CEILING_MS,
    )
    /** The decision hold announces itself in the log once, not once per poll. */
    private var entityBootstrapDecisionLogged = false
    private var entityBootstrapProblem: EntityBootstrapProblem? = null
    private var entityBootstrapReadEpoch = 0L
    // A fresh automatic learner has no safe document-start allow-list yet. Keep a native screen in
    // front rather than load HA unfiltered on a whim; this poll is a backstop for a missed/blocked service
    // relaunch when synchronization commits the first set. The hold's time limit is a separate timer
    // ([entityHoldDeadline]), so a poll that stops can never make the hold permanent.
    private val entityBootstrapCheck = object : Runnable {
        override fun run() {
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return
            val readEpoch = ++entityBootstrapReadEpoch
            scope.launch { pollEntityBootstrap(readEpoch) }
        }
    }

    /** One bootstrap poll: keep holding (and re-post [entityBootstrapCheck]), or build the renderer. */
    internal suspend fun pollEntityBootstrap(readEpoch: Long = ++entityBootstrapReadEpoch) {
        val config = readActivityStateOffMain { readConfig() }
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
        if (shouldHoldRendererForEntityBootstrap(config)) {
            // The hold is structurally forbidden from being terminal. The enable-time sync has died
            // silently on hardware more than once, each time leaving a happy spinner over a dead
            // process until a human intervened. At 30 seconds fire the same recovery the manual sync
            // endpoint provides; the hold's time limit ([entityHoldDeadline]) is a separate timer.
            //
            // Firing the recovery once was not enough. The commonest reason the scan cannot answer is
            // that Home Assistant is not up yet — a power cut brings the panel back first — and a
            // one-shot retry means the panel is still holding, or already showing its dashboard unfiltered,
            // long after HA returned. Nothing else re-drives it: the periodic sync skips a freshly-synced
            // catalogue and skips again while the screen is awake, and no HA-reconnect trigger exists.
            // So keep retrying on a widening interval, capped, for as long as the hold lasts.
            //
            // Unless the scan HAS answered and a flagged rule is waiting on a person. A resync cannot
            // supply that decision, and one panel performed 794 of them over 2.75 days waiting for
            // somebody to walk up to it. There the watchdog asks Home Assistant, on a much wider
            // cadence, whether the dashboard itself changed, and otherwise stays quiet. The state
            // machine owns every clock; see EntityBootstrapWatchdog.
            val now = timers.now()
            val blocking = learner.blockingIssueCount()
            val problem = readActivityStateOffMain { learner.bootstrapProblem() }
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
            val action = entityBootstrapWatchdog.tick(
                nowMs = now,
                blockingIssues = blocking,
                problem = problem,
                syncRunning = learner.syncRunning(),
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
                    scope.launch(Dispatchers.IO) { learner.retryBootstrap() }
                }
                EntityBootstrapWatchdogAction.PROBE -> {
                    Log.i(TAG, "entity bootstrap held on a decision — dashboard change probe ${entityBootstrapWatchdog.probes}")
                    scope.launch(Dispatchers.IO) { learner.probeDashboardChange() }
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
                val milestone = readActivityStateOffMain { learner.bootstrapMilestoneState() }
                if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
                paintEntityLearning(milestone)
            }
            timers.post(entityBootstrapCheck, ENTITY_BOOTSTRAP_CHECK_MS)
            return
        }
        entityBootstrapWatchdog.reset()
        entityBootstrapDecisionLogged = false
        // Build even while the panel is dark: buildAndLoad owns dark settling (the load runs live for a
        // window, then freezes), as on any cold start. Waiting for a wake here left a dark panel on the
        // hold screen, with no dashboard, until someone walked past it.
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
        val filterEpoch = ++filterPreparationEpoch
        val prepared = prepareEntityFilter(config)
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
        if (filterEpoch != filterPreparationEpoch) {
            // A relaunch superseded this preparation. It builds only when it finds something to rebuild: a plain
            // return to the foreground (Panel Assistant's post-update return, HOME, a kiosk return) leaves the hold
            // screen to this poll. Returning for good here left an updated panel on the hold screen with a
            // verified, applied set. Keep polling until a renderer is admitted.
            if (!rendererAdmitted) {
                timers.cancel(entityBootstrapCheck)
                timers.post(entityBootstrapCheck, ENTITY_BOOTSTRAP_CHECK_MS)
            }
            return
        }
        configureEntityFilter(config, prepared)
        host.buildAndLoad(config)
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
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return
            val config = readConfig()
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
            // Answered: the wizard has nothing left to come back for.
            SetupWizardResume.clear()
            host.buildAndLoad(config)
        }
    }
    private val entityFilterRetry = Runnable {
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || !host.screenAwake || entityFilterNativeHold == null) return@Runnable
        entityFilterRetryDueAtMs = 0L
        entityFilterRetryPolicy.recordAttempt()
        retryEntityFilter(readConfig())
    }

    suspend fun prepareEntityFilter(config: Config): PreparedEntityFilter = prepareEntityFilterOffMain(read = {
        // A released hold opens the dashboard unfiltered; its own clearing rebuilds it filtered.
        val enabled = config.dashboardEntityFilterEnabled && RendererEntityHold.released == null
        val haUrl = config.haEffectiveUrl
        EntityFilterPreparationInput(
            enabled = enabled,
            learningEnabled = config.dashboardEntityLearningEnabled,
            ids = if (enabled) config.dashboardEntityFilterIds else emptyList(),
            haUrl = haUrl,
            origins = if (enabled) dashboardDocumentStartOrigins(haUrl, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config)) else emptySet(),
        )
    })

    /** Prepare the exact allow-list for document-start interception. Automatic filtering fails closed:
     *  an unavailable interceptor holds the native diagnostic screen rather than opening HA unfiltered. */
    fun configureEntityFilter(config: Config, prepared: PreparedEntityFilter) {
        entityFilterNativeHold = null
        preparedEntityFilter = prepared
        entityFilterSignature = prepared.signature
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
            } else if (entityFilterSignature.startsWith("invalid")) {
                Log.e(TAG, "invalid entity-filter configuration")
                entityFilterSignature = "disabled"
                EntityFilterTelemetry.failed(lease, "invalid_configuration")
                EntityFilterTelemetry.directFallback(lease)
            }
            return
        }
        val ids = prepared.ids
        val lease = EntityFilterTelemetry.started(ids, prepared.hash)
        entityFilterLease = lease
        if (!documentStartSupported()) {
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
        timers.cancel(entityFilterRetry)
        val filterEpoch = ++filterPreparationEpoch
        scope.launch {
            val prepared = prepareEntityFilter(config)
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || filterEpoch != filterPreparationEpoch) return@launch
            entityFilterLease?.let(EntityFilterTelemetry::stop)
            entityFilterLease = null
            configureEntityFilter(config, prepared)
            host.buildAndLoad(config)
        }
    }

    private fun scheduleEntityFilterRetry() {
        // Carry the deadline rather than the delay. This is re-entered by the status rerender, which a
        // theme write or a rotation triggers, and re-posting at the FULL delay each time means a rung
        // measured in minutes never elapses on a panel whose theme is being written. The same defect was
        // fixed for the admission timer; it was not fixed here.
        timers.cancel(entityFilterRetry)
        val now = timers.now()
        val remaining = entityFilterRetryDueAtMs - now
        val delay = if (entityFilterRetryDueAtMs > 0L && remaining > 0L) {
            remaining
        } else {
            val fresh = entityFilterRetryPolicy.nextDelay(host.screenAwake) ?: return
            entityFilterRetryDueAtMs = now + fresh
            fresh
        }
        timers.post(entityFilterRetry, delay)
    }

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
        val config = readConfig()
        config.setupEntityFilterAnswered = true
        config.setupHomeDashboardChosen = true
        // Skipping declines the wizard: nothing may bring it back on its own after this.
        SetupWizardResume.clear()
        main.removeCallbacks(entityFilterAnswerCheck)
        host.buildAndLoad(config)
    }

    /**
     * The pre-render screen shown while the wizard's entity-filter question is open.
     *
     * Says what it is waiting for and that the wait is not the panel's fault, because someone walking up to a
     * panel mid-setup must be able to tell a deliberate pause from a hang. Static by design: no WebView, no
     * network, nothing to churn — it exists only so the first dashboard the user sees is the filtered one.
     */
    fun showWaitingForEntityFilterAnswer() {
        main.removeCallbacks(entityBootstrapCheck)
        main.removeCallbacks(entityFilterAnswerCheck)
        host.teardownWeb()
        paintQuestion(
            listOf(
                // The way back to the questions. Signing in on the panel brings this screen over the panel's
                // own setup wizard, and with only Skip on it the wizard was unreachable from the panel: the
                // questions went unanswered, setup never finished, and Home Assistant would not add the
                // panel. Only shown before setup first completes, so the wizard is the right destination.
                R.string.set_up to { openSettings(SETUP_WIZARD_PATH) },
                // Escape hatch. The hold exists to protect the first impression, not to trap anyone: someone
                // at the panel who does not want to answer must be able to get a dashboard. Says what it
                // costs, because skipping is the unfiltered load this screen exists to avoid.
                R.string.skip_load_dashboard to { skipEntityFilterQuestion() },
            ),
        )
        // Straight back to the wizard when this screen only came up because something covered it: the on-panel
        // sign-in, or a relaunch from one of the wizard's own answers. The screen stays painted underneath, so
        // leaving the wizard with Back lands here, on Set up and Skip.
        if (SetupWizardResume.armed()) openSettings(SETUP_WIZARD_PATH)
        main.postDelayed(entityFilterAnswerCheck, ENTITY_FILTER_ANSWER_CHECK_MS)
    }

    private fun paintEntityFilterQuestion(actions: List<Pair<Int, () -> Unit>>) {
        statusScreens.cancelAdmissionAutoRetry()
        val surface = statusScreens.statusSurface(readConfig())
        val notice = surface.notice
        surface.setBody(
            activity.getString(R.string.notice_pill_setup),
            notice.hero(activity.getString(R.string.notice_hero_almost_there), word = true),
            notice.line(activity.getString(R.string.notice_line_setup_question)),
            notice.actions(*actions.map { (label, run) -> notice.action(activity.getString(label)) { run() } }.toTypedArray()),
            notice.footer(activity.getString(R.string.skip_entity_filter_detail)),
        )
        statusScreens.showStatusSurface(surface) { showWaitingForEntityFilterAnswer() }
    }

    private val entityHoldActions = object : EntityHoldActions {
        override fun openSettings(path: String) = this@DashboardEntityGate.openSettings(path)
        override fun ignoreFlagged() = EntityLearningRuntime.ignoreBlockingIssues()
        override fun disableFilter() = EntityLearningRuntime.disableAutomaticFilter()
        override fun retryFilter() = retryEntityFilter(readConfig())
        override fun retryScan() {
            // A user-driven retry restores hope: the watchdog clock restarts and the screen returns to the
            // progress presentation until the new deadline.
            entityBootstrapWatchdog.restart(timers.now())
            scope.launch {
                val started = withContext(Dispatchers.IO) { learner.retryBootstrap() }
                if (started && !host.destroyed) showWaitingForEntityBootstrap()
            }
        }
    }

    /** Show the first scan's milestone: the count read so far as the hero, the step on the track. */
    private fun paintEntityLearning(milestone: EntityLearningRuntime.BootstrapMilestone?) {
        val learning = entityLearningNotice(milestone?.stage, milestone?.count, milestone?.expectedTotal)
        val numbers = java.text.NumberFormat.getIntegerInstance()
        entityBootstrapMilestoneView?.text = learning.count?.let(numbers::format)
            ?: activity.getString(R.string.notice_hero_reading)
        entityLearningLine?.text = learning.expectedTotal?.let {
            activity.getString(learning.line, numbers.format(learning.count), numbers.format(it))
        } ?: activity.getString(learning.line)
        entityLearningTrack?.show(learning.step, learning.fills)
    }

    private fun localizedEntityFilterHoldDetail(hold: EntityFilterNativeHold): String = when (hold.error) {
        "invalid_configuration" -> activity.getString(R.string.entity_filter_invalid_config)
        "document_start_unsupported" -> activity.getString(R.string.entity_filter_webview_too_old)
        "document_start_install" -> activity.getString(R.string.entity_filter_setup_failed)
        else -> hold.detail
    }

    fun showWaitingForEntityBootstrap() {
        val readEpoch = ++entityBootstrapReadEpoch
        scope.launch {
            val blockingIssues = learner.blockingIssueCount()
            val read = readActivityStateOffMain {
                Triple(
                    learner.bootstrapProblem(),
                    blockingIssues > 0 && learner.canIgnoreBlockingIssues(),
                    learner.bootstrapMilestoneState(),
                )
            }
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return@launch
            renderWaitingForEntityBootstrap(blockingIssues, read.first, read.second, read.third)
        }
    }

    private fun renderWaitingForEntityBootstrap(
        blockingIssues: Int,
        problem: EntityBootstrapProblem?,
        canIgnoreBlockingIssues: Boolean,
        milestone: EntityLearningRuntime.BootstrapMilestone?,
    ) {
        timers.cancel(entityBootstrapCheck)
        host.teardownWeb()
        rendererAdmitted = false
        val filterHold = entityFilterNativeHold
        entityBootstrapBlockedCount = blockingIssues
        entityBootstrapProblem = problem
        val kind = entityHoldKind(blockingIssues, filterHold != null, problem, canIgnoreBlockingIssues)
        holdShown = kind
        entityBootstrapMilestoneView = null
        entityLearningLine = null
        entityLearningTrack = null
        val learning = painter.paint(kind, blockingIssues, filterHold)
        if (learning != null) {
            // This screen says, before the burst starts, that the dashboard may reload while the panel learns;
            // the reloads that follow it stay quiet rather than each announcing a restart.
            BuiltinDashboard.restartAnnouncements.learningNoticeShown(timers.now())
            entityBootstrapMilestoneView = learning.hero
            entityLearningLine = learning.line
            entityLearningTrack = learning.track
            paintEntityLearning(milestone)
            // The happy wait needs an honesty rung of its own: watched live, a stuck first scan held this screen
            // for many minutes with no acknowledgement, indistinguishable from a hang. Text only.
            main.postDelayed({
                if (!host.destroyed && learning.hint.isAttachedToWindow) learning.hint.text = activity.getString(R.string.entity_scan_taking_longer, BOOTSTRAP_HOLD_HONESTY_MS / 1_000L)
            }, BOOTSTRAP_HOLD_HONESTY_MS)
        }
        armEntityHoldDeadline(kind)
        if (filterHold != null) scheduleEntityFilterRetry()
        else timers.post(entityBootstrapCheck, ENTITY_BOOTSTRAP_CHECK_MS)
        Log.i(TAG, if (filterHold != null) {
            "automatic entity filter held before WebView creation (${filterHold.error})"
        } else if (blockingIssues > 0) {
            "automatic entity filter awaiting a choice for $blockingIssues safety issue(s)"
        } else {
            "automatic entity set not ready — holding renderer before WebView creation"
        })
    }

    /** The production hold screen: the Notice for [kind], with its actions and the first scan's live views. */
    private fun paintHoldScreen(kind: EntityHoldKind, blockingIssues: Int, filterHold: EntityFilterNativeHold?): EntityLearningViews? {
        statusScreens.cancelAdmissionAutoRetry()
        val surface = statusScreens.statusSurface()
        val (rows, learning) = entityHoldRows(
            activity, surface.notice, kind, blockingIssues, filterHold?.let(::localizedEntityFilterHoldDetail),
            scope, alive = { !host.destroyed }, actions = entityHoldActions,
        )
        surface.setBody(activity.getString(kind.pill), *rows.toTypedArray())
        statusScreens.showStatusSurface(surface) { showWaitingForEntityBootstrap() }
        return learning
    }

    /**
     * Every hold has one deadline, [RendererEntityHold.BOUND_MS] after it was first shown. It is its own
     * timer rather than a rung of the bootstrap poll, because each way a hold has stuck on hardware was a
     * retry that stopped: the poll superseded by a relaunch, the filter retry's finite ladder, a retry
     * suspended while dark. It carries its due time, so a redraw or a recreated activity never restarts it,
     * and it runs while the panel is dark, as the release build does.
     */
    private fun armEntityHoldDeadline(kind: EntityHoldKind) {
        val now = timers.now()
        val dueAt = RendererEntityHold.holding(kind.wire, now) + RendererEntityHold.BOUND_MS
        timers.cancel(entityHoldDeadline)
        timers.post(entityHoldDeadline, (dueAt - now).coerceAtLeast(0L))
    }

    private val entityHoldDeadline = Runnable {
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || holdShown == null) return@Runnable
        scope.launch { releaseEntityHold() }
    }

    /**
     * The hold's time limit passed: open the dashboard. A hold whose cause cleared while nothing noticed
     * builds as it is; one still standing is released, so the renderer opens unfiltered and learning keeps
     * retrying behind it ([pollReleasedEntityHold]).
     */
    internal suspend fun releaseEntityHold() {
        val kind = holdShown ?: return
        val config = readActivityStateOffMain { readConfig() }
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || holdShown == null) return
        timers.cancel(entityBootstrapCheck)
        timers.cancel(entityFilterRetry)
        ++entityBootstrapReadEpoch // a poll still in flight belongs to the hold being ended
        if (holdsRenderer(config)) {
            RendererEntityHold.release(kind.wire, timers.now(), entityFilterKey(config))
            Log.w(
                TAG,
                "entity hold ${kind.wire} stood ${RendererEntityHold.BOUND_MS / 1_000L} s — opening the dashboard " +
                    "unfiltered while learning keeps retrying",
            )
        } else {
            Log.w(TAG, "entity hold ${kind.wire} outlived its cause — building the dashboard")
        }
        stopLease()
        val filterEpoch = ++filterPreparationEpoch
        val prepared = prepareEntityFilter(config)
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return
        if (filterEpoch != filterPreparationEpoch) {
            // Superseded by a relaunch, which builds only when it finds something to rebuild: try again.
            if (!rendererAdmitted) timers.post(entityHoldDeadline, ENTITY_BOOTSTRAP_CHECK_MS)
            return
        }
        configureEntityFilter(config, prepared)
        host.buildAndLoad(config)
    }

    /** While a hold stands released, learning keeps retrying it, and its clearing rebuilds the dashboard filtered. */
    private val releasedEntityHoldCheck = Runnable {
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return@Runnable
        scope.launch { pollReleasedEntityHold() }
    }

    internal suspend fun pollReleasedEntityHold() {
        val released = RendererEntityHold.released ?: return
        val config = readActivityStateOffMain { readConfig() }
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner)) return
        // A filter hold failed on one allow-list, so only another list, or none, can clear it; a learning hold
        // clears when learning has a list it can vouch for.
        val cleared = if (released.kind == EntityHoldKind.FILTER_HOLD.wire) {
            entityFilterKey(config) != released.key
        } else {
            !entityLearningHold(config)
        }
        if (cleared) {
            RendererEntityHold.ended()
            entityBootstrapWatchdog.reset()
            Log.i(TAG, "entity hold ${released.kind} cleared — rebuilding the dashboard filtered")
            host.reconcile()
            return
        }
        if (entityLearningHold(config)) {
            val blocking = learner.blockingIssueCount()
            val problem = readActivityStateOffMain { learner.bootstrapProblem() }
            when (entityBootstrapWatchdog.tick(timers.now(), blocking, problem, learner.syncRunning())) {
                EntityBootstrapWatchdogAction.RESYNC -> {
                    Log.w(TAG, "entity hold released — watchdog resync ${entityBootstrapWatchdog.resyncs}")
                    scope.launch(Dispatchers.IO) { learner.retryBootstrap() }
                }
                EntityBootstrapWatchdogAction.PROBE -> scope.launch(Dispatchers.IO) { learner.probeDashboardChange() }
                EntityBootstrapWatchdogAction.IDLE -> Unit
            }
        }
        timers.post(releasedEntityHoldCheck, RELEASED_HOLD_CHECK_MS)
    }

    /** What a filter hold failed on: the filter settings, without preparing them. */
    private fun entityFilterKey(config: Config): String =
        "${config.dashboardEntityLearningEnabled}:${config.dashboardEntityFilterEnabled}:${config.dashboardEntityFilterIds.hashCode()}"

    /** A relaunch began: a filter preparation still in flight belongs to the superseded intent. */
    fun supersedePreparation() {
        ++filterPreparationEpoch
    }

    /** The renderer must stay behind a native hold screen rather than open Home Assistant. */
    fun holdsRenderer(config: Config): Boolean = shouldHoldRendererForEntityBootstrap(config) || entityFilterNativeHold != null

    /**
     * Admission let a dashboard WebView be built: the bootstrap poll has nothing left to wait for. The hold's
     * clock keeps running until the WebView exists ([onRendererCreated]): a filter that fails to install at
     * creation paints the hold again, and its retries must not buy a fresh time limit each round.
     */
    fun onRendererAdmitted() {
        timers.cancel(entityBootstrapCheck)
        timers.cancel(entityHoldDeadline)
        entityBootstrapBlockedCount = -1
        rendererAdmitted = true
        holdShown = null
        if (RendererEntityHold.released != null) {
            timers.cancel(releasedEntityHoldCheck)
            timers.post(releasedEntityHoldCheck, RELEASED_HOLD_CHECK_MS)
        }
    }

    /** The dashboard WebView was created with whatever filter it carries: an unreleased hold has ended. */
    fun onRendererCreated() {
        if (RendererEntityHold.released == null) RendererEntityHold.ended()
    }

    fun stopLease() {
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
    }

    /** A dark panel spends no retry; a wake resumes the pending one. */
    fun onScreenChanged(awake: Boolean) {
        if (awake && entityFilterNativeHold != null) scheduleEntityFilterRetry()
        if (!awake) timers.cancel(entityFilterRetry)
    }

    /**
     * Install the subscription interceptor and the traffic observer on a WebView being created. Throws
     * [EntityFilterInterceptorUnavailable], after destroying [view], when automatic filtering must hold the
     * native screen instead of opening Home Assistant unfiltered.
     */
    fun installDocumentStartFilter(view: WebView, config: Config, documentStartOrigins: Set<String>) {
        // Intercept only the primary HA socket's outbound subscribe_entities command. The socket itself
        // remains Chromium-native, preserving its TLS, compression and external-app lifecycle signals.
        val filterLease = entityFilterLease
        if (filterLease != null && EntityFilterTelemetry.isActive(filterLease)) {
            try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    view,
                    requireNotNull(preparedEntityFilter?.script).getOrThrow(),
                    documentStartOrigins,
                )
                entityFilterRetryPolicy.reset()
            } catch (error: Throwable) {
                if (fallbackFromEntityFilterInterceptor(error, config.dashboardEntityLearningEnabled)) {
                    runCatching { view.destroy() }
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
            if (documentStartSupported()) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    view,
                    EntityFilterProtocol.trafficObserverDocumentStartScript(config.haEffectiveUrl, documentStartOrigins),
                    documentStartOrigins,
                )
                EntityFilterTelemetry.trafficObserverInstalled(requireNotNull(filterLease))
            }
        }.onFailure { Log.w(TAG, "entity-filter traffic observer unavailable", it) }
    }

    private companion object {
        const val TAG = DashboardActivity.TAG
        // Tightened once commit-from-catalog made the happy bootstrap take milliseconds: the net now
        // assumes seconds are normal and anything past twenty is a fault.
        const val BOOTSTRAP_HOLD_HONESTY_MS = 20_000L
        const val BOOTSTRAP_WATCHDOG_RETRY_MS = 30_000L
        /** The widening retry never idles longer than this, so a panel converges once HA answers. */
        const val BOOTSTRAP_WATCHDOG_RETRY_CEILING_MS = 300_000L
        // While the hold waits on a person's decision, the only useful question is whether the dashboard
        // changed. One authenticated read, five minutes after the hold settles, then widening to hourly.
        const val BOOTSTRAP_WATCHDOG_PROBE_MS = 300_000L
        const val BOOTSTRAP_WATCHDOG_PROBE_CEILING_MS = 3_600_000L
        const val ENTITY_BOOTSTRAP_CHECK_MS = 1_000L    // missed-relaunch backstop; no network work
        // Behind a released hold: settings and the learner's state are local reads; resyncs keep the watchdog's ladder.
        const val RELEASED_HOLD_CHECK_MS = 5_000L
        // Backstop only: answering normally relaunches the renderer from the server. This catches the case
        // where that relaunch is missed, so the panel still proceeds on its own. Local pref read, no network.
        const val ENTITY_FILTER_ANSWER_CHECK_MS = 1_500L
    }
}
