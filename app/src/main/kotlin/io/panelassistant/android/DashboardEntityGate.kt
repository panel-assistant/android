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

/**
 * The entity-filter gate in front of the dashboard WebView: it prepares the document-start allow-list,
 * decides whether automatic filtering must hold the renderer behind a native screen, keeps that hold and
 * its retry and bootstrap poll, and installs the interceptor on the WebView once the renderer may open.
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
) {
    interface Host {
        val activityOwner: Long
        val destroyed: Boolean
        val screenAwake: Boolean
        fun buildAndLoad(config: Config)
        fun teardownWeb()
    }

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
    private var entityBootstrapMilestoneView: TextView? = null
    private var entityLearningLine: TextView? = null
    private var entityLearningTrack: NoticeTrack? = null
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
    private var entityBootstrapReadEpoch = 0L
    // A fresh automatic learner has no safe document-start allow-list yet. Keep a native screen in
    // front instead of ever loading HA unfiltered; this poll is a backstop for a missed/blocked service
    // relaunch when synchronization commits the first set.
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
            val problem = readActivityStateOffMain { EntityLearningRuntime.bootstrapProblem() }
            if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
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
                    scope.launch(Dispatchers.IO) { EntityLearningRuntime.retryBootstrap() }
                }
                EntityBootstrapWatchdogAction.PROBE -> {
                    Log.i(TAG, "entity bootstrap held on a decision — dashboard change probe ${entityBootstrapWatchdog.probes}")
                    scope.launch(Dispatchers.IO) { EntityLearningRuntime.probeDashboardChange() }
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
                val milestone = readActivityStateOffMain { EntityLearningRuntime.bootstrapMilestoneState() }
                if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) || readEpoch != entityBootstrapReadEpoch) return
                paintEntityLearning(milestone)
            }
            main.postDelayed(entityBootstrapCheck, ENTITY_BOOTSTRAP_CHECK_MS)
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
        if (host.destroyed || !BuiltinDashboard.ownsActivity(host.activityOwner) ||
            readEpoch != entityBootstrapReadEpoch || filterEpoch != filterPreparationEpoch) return
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
        val enabled = config.dashboardEntityFilterEnabled
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
        main.removeCallbacks(entityFilterRetry)
        val now = SystemClock.elapsedRealtime()
        val remaining = entityFilterRetryDueAtMs - now
        val delay = if (entityFilterRetryDueAtMs > 0L && remaining > 0L) {
            remaining
        } else {
            val fresh = entityFilterRetryPolicy.nextDelay(host.screenAwake) ?: return
            entityFilterRetryDueAtMs = now + fresh
            fresh
        }
        main.postDelayed(entityFilterRetry, delay)
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
        val config = readConfig()
        config.setupEntityFilterAnswered = true
        config.setupHomeDashboardChosen = true
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
        statusScreens.cancelAdmissionAutoRetry()
        main.removeCallbacks(entityBootstrapCheck)
        main.removeCallbacks(entityFilterAnswerCheck)
        host.teardownWeb()
        val config = readConfig()
        val surface = statusScreens.statusSurface(config)
        val notice = surface.notice
        surface.setBody(
            activity.getString(R.string.notice_pill_setup),
            notice.hero(activity.getString(R.string.notice_hero_almost_there), word = true),
            notice.line(activity.getString(R.string.notice_line_setup_question)),
            // Escape hatch. The hold exists to protect the first impression, not to trap anyone: someone at
            // the panel with no browser to hand must be able to get a dashboard. Says what it costs, because
            // skipping is the unfiltered load this screen exists to avoid.
            notice.actions(notice.action(activity.getString(R.string.skip_load_dashboard)) { skipEntityFilterQuestion() }),
            notice.footer(activity.getString(R.string.skip_entity_filter_detail)),
        )
        statusScreens.showStatusSurface(surface) { showWaitingForEntityFilterAnswer() }
        main.postDelayed(entityFilterAnswerCheck, ENTITY_FILTER_ANSWER_CHECK_MS)
    }

    private val entityHoldActions = object : EntityHoldActions {
        override fun openSettings(path: String) {
            activity.startActivity(Intent(activity, ConfigActivity::class.java).putExtra("path", path))
        }
        override fun ignoreFlagged() = EntityLearningRuntime.ignoreBlockingIssues()
        override fun disableFilter() = EntityLearningRuntime.disableAutomaticFilter()
        override fun retryFilter() = retryEntityFilter(readConfig())
        override fun retryScan() {
            // A user-driven retry restores hope: the watchdog clock restarts and the screen returns to the
            // progress presentation until the new deadline.
            entityBootstrapWatchdog.restart(SystemClock.elapsedRealtime())
            scope.launch {
                val started = withContext(Dispatchers.IO) { EntityLearningRuntime.retryBootstrap() }
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
            val blockingIssues = EntityLearningRuntime.blockingIssueCount()
            val read = readActivityStateOffMain {
                Triple(
                    EntityLearningRuntime.bootstrapProblem(),
                    blockingIssues > 0 && EntityLearningRuntime.canIgnoreBlockingIssues(),
                    EntityLearningRuntime.bootstrapMilestoneState(),
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
        statusScreens.cancelAdmissionAutoRetry()
        main.removeCallbacks(entityBootstrapCheck)
        host.teardownWeb()
        val filterHold = entityFilterNativeHold
        // Past the watchdog's give-up deadline a formless hold PRESENTS as a synchronization problem so
        // the retry/disable buttons appear — the happy spinner must never be terminal.
        val bootstrapProblem = problem
            ?: if (entityBootstrapWatchdog.gaveUp) EntityBootstrapProblem.SYNCHRONIZATION else null
        entityBootstrapBlockedCount = blockingIssues
        entityBootstrapProblem = bootstrapProblem
        val surface = statusScreens.statusSurface()
        val notice = surface.notice
        val kind = entityHoldKind(blockingIssues, filterHold != null, bootstrapProblem, canIgnoreBlockingIssues)
        entityBootstrapMilestoneView = null
        entityLearningLine = null
        entityLearningTrack = null
        val (rows, learning) = entityHoldRows(
            activity, notice, kind, blockingIssues, filterHold?.let(::localizedEntityFilterHoldDetail),
            scope, alive = { !host.destroyed }, actions = entityHoldActions,
        )
        if (learning != null) {
            // This screen says, before the burst starts, that the dashboard may reload while the panel learns;
            // the reloads that follow it stay quiet rather than each announcing a restart.
            BuiltinDashboard.restartAnnouncements.learningNoticeShown(SystemClock.elapsedRealtime())
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
        surface.setBody(activity.getString(kind.pill), *rows.toTypedArray())
        statusScreens.showStatusSurface(surface) { showWaitingForEntityBootstrap() }
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

    /** A relaunch began: a filter preparation still in flight belongs to the superseded intent. */
    fun supersedePreparation() {
        ++filterPreparationEpoch
    }

    /** The renderer must stay behind a native hold screen rather than open Home Assistant. */
    fun holdsRenderer(config: Config): Boolean = shouldHoldRendererForEntityBootstrap(config) || entityFilterNativeHold != null

    /** Admission let a dashboard WebView be built: the bootstrap poll has nothing left to wait for. */
    fun onRendererAdmitted() {
        main.removeCallbacks(entityBootstrapCheck)
        entityBootstrapBlockedCount = -1
    }

    fun stopLease() {
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
    }

    /** A dark panel spends no retry; a wake resumes the pending one. */
    fun onScreenChanged(awake: Boolean) {
        if (awake && entityFilterNativeHold != null) scheduleEntityFilterRetry()
        if (!awake) main.removeCallbacks(entityFilterRetry)
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
            if (webViewFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
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
        const val BOOTSTRAP_WATCHDOG_PROBLEM_MS = 90_000L
        /** The widening retry never idles longer than this, so a panel converges once HA answers. */
        const val BOOTSTRAP_WATCHDOG_RETRY_CEILING_MS = 300_000L
        // While the hold waits on a person's decision, the only useful question is whether the dashboard
        // changed. One authenticated read, five minutes after the hold settles, then widening to hourly.
        const val BOOTSTRAP_WATCHDOG_PROBE_MS = 300_000L
        const val BOOTSTRAP_WATCHDOG_PROBE_CEILING_MS = 3_600_000L
        const val ENTITY_BOOTSTRAP_CHECK_MS = 1_000L    // missed-relaunch backstop; no network work
        // Backstop only: answering normally relaunches the renderer from the server. This catches the case
        // where that relaunch is missed, so the panel still proceeds on its own. Local pref read, no network.
        const val ENTITY_FILTER_ANSWER_CHECK_MS = 1_500L
    }
}
