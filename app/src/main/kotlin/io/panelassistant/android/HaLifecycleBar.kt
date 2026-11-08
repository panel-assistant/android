package io.panelassistant.android

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleRuntime
import io.panelassistant.android.sensors.HaLifecycleState

/**
 * How long this panel's dashboard has been disconnected from Home Assistant, or null while it is not, or
 * when the drop came before a restart's ready notice ([snap]) under [HaLifecycle.RESTART_RELOAD_MS] ago:
 * that restart explains it, and the dashboard is simply reloading after it.
 */
internal fun haDashboardLostMs(snap: HaLifecycle.Snapshot?, renderer: RendererAdmissionRuntime.Live?, nowMs: Long): Long? =
    renderer?.takeIf { !it.frontendConnected }?.frontendLostAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
        ?.takeUnless { lost -> snap?.readyAgoMs?.let { lost >= it && it < HaLifecycle.RESTART_RELOAD_MS } == true }

/**
 * Select the notice shown over the dashboard from the current owners' observations.
 *
 * Panel Assistant's lifecycle notices come first. A panel without a live Panel Assistant session gets
 * none, so its own dashboard dropping is the last rule: past the offline grace it says it cannot reach
 * Home Assistant, and a notice arriving later replaces that with the restart card.
 */
internal fun haLifecycleNoticeState(
    snap: HaLifecycle.Snapshot?,
    renderer: RendererAdmissionRuntime.Live?,
    nowMs: Long = SystemClock.elapsedRealtime(),
): HaLifecycleState? = when {
    snap?.state == HaLifecycleState.SHUTTING_DOWN || snap?.state == HaLifecycleState.STARTING -> snap.state
    renderer?.phase == RendererPhase.AUTH_LATCHED -> null
    snap?.state == HaLifecycleState.CONNECTION_LOST && snap.offlineGraceRemainingMs > 0L -> null
    renderer?.record?.state == RendererAdmissionState.ADMITTED &&
        renderer.record.admittedOnCachedVersion && !renderer.frontendConnected -> HaLifecycleState.CONNECTION_LOST
    snap?.state == HaLifecycleState.CONNECTION_LOST && snap.offlineGraceRemainingMs <= 0L -> snap.state
    // The last step lasts while the dashboard reloads and ends the moment it reconnects: a connected
    // dashboard is usable, and a card still covering it would be Panel Assistant getting in the way.
    snap?.state == HaLifecycleState.BACK_ONLINE && renderer?.frontendConnected != true -> snap.state
    (haDashboardLostMs(snap, renderer, nowMs) ?: 0L) >= HaLifecycle.OFFLINE_GRACE_MS -> HaLifecycleState.CONNECTION_LOST
    else -> null
}

/** The three steps of a restart the card shows, in order. */
internal enum class HaRestartStep { STOPPING, STARTING, RELOADING }

/**
 * What the restart card shows, decided from the lifecycle snapshot alone.
 *
 * Pure and Android-free, so every state is assertable without inflating a view — unit-tested in
 * `HaLifecycleCardTest`. The view only turns this into words and pixels.
 *
 * @property step the current step, or null for an outage nothing explained (no track is shown)
 * @property fills each step's fill, out of [HA_LIFECYCLE_PROGRESS_MAX]
 * @property remainingMs time left to the measured usual time; null when not measured, overdue or ready
 * @property overdueMs how far past the usual time; null unless overdue
 * @property usualMs the measured usual time
 * @property restartedInMs once Home Assistant is ready and the dashboard is still catching up, how long
 *   this restart took
 * @property offlineMs for an outage nothing explained, how long the dashboard has been disconnected, when known
 */
internal data class HaLifecycleCard(
    val step: HaRestartStep?,
    val fills: List<Int>,
    val remainingMs: Long?,
    val overdueMs: Long?,
    val usualMs: Long?,
    val restartedInMs: Long?,
    val offlineMs: Long? = null,
)

internal const val HA_LIFECYCLE_PROGRESS_MAX = 10_000

/**
 * Decide the card for [state].
 *
 * Reloading is when the dashboard redraws. Home Assistant reconnects the dashboard while it is still
 * starting (seen on hardware: cameras live and lights "Unavailable" a minute before Panel Assistant
 * reported it ready), so a reconnected dashboard during startup IS the Reloading step, and the card
 * closes when Home Assistant is ready. Only if the dashboard has not reconnected by then does Reloading
 * continue over the back-online window.
 *
 * The track is ONE timeline: elapsed ÷ usual time, spread evenly across the three parts, so every panel
 * shows the same position at the same moment. Nothing this panel alone knows moves it: an earlier
 * version filled each step from when this panel showed it, and another forced a finished step full when
 * this panel's dashboard reconnected, and in both panels watching the same restart disagreed. Only the
 * whole restart is measured, not each step, so the fill can run ahead of or behind this panel's step; the
 * pill and the bold step name say which step it is in. The track is full past the usual time (progress
 * is capped there) and once Home Assistant is ready, and never runs backwards. With no measurement there
 * is no timeline: no countdown, and the track only marks the steps already behind it.
 *
 * @param dashboardConnected whether this panel's dashboard is connected to Home Assistant right now
 * @param dashboardLostMs how long this panel's dashboard has been disconnected, or null
 */
internal fun haLifecycleCard(
    state: HaLifecycleState,
    snap: HaLifecycle.Snapshot?,
    dashboardConnected: Boolean = false,
    backOnlineWindowMs: Long = HaLifecycle.DEFAULT_BACK_ONLINE_WINDOW_MS,
    dashboardLostMs: Long? = null,
): HaLifecycleCard {
    val step = when (state) {
        HaLifecycleState.SHUTTING_DOWN -> HaRestartStep.STOPPING
        HaLifecycleState.STARTING -> if (dashboardConnected) HaRestartStep.RELOADING else HaRestartStep.STARTING
        HaLifecycleState.BACK_ONLINE -> HaRestartStep.RELOADING
        HaLifecycleState.CONNECTION_LOST, HaLifecycleState.NORMAL -> null
    }
    val ready = state == HaLifecycleState.BACK_ONLINE
    val expected = snap?.expectedMs?.takeIf { it > 0L }
    val elapsed = snap?.elapsedMs?.coerceAtLeast(0L)
    val measured = expected != null && elapsed != null
    val max = HA_LIFECYCLE_PROGRESS_MAX
    val progress = if (measured) (minOf(elapsed!!, expected!!) * max / expected).toInt() else 0
    val overdue = measured && !ready && step != null && elapsed!! > expected!!
    val fills = when {
        step == null -> listOf(0, 0, 0)
        // Ready is reported to every panel at once, so a full track is the same everywhere too.
        ready -> listOf(max, max, max)
        measured -> List(3) { i -> (progress * 3 - i * max).coerceIn(0, max) }
        // Without a measurement there is no timeline; the track only marks the steps already behind it.
        else -> List(3) { i -> if (i < step.ordinal) max else 0 }
    }
    val restartedIn = if (ready && elapsed != null) {
        val sinceBack = backOnlineWindowMs - (snap?.backOnlineRemainingMs ?: 0L).coerceIn(0L, backOnlineWindowMs)
        (elapsed - sinceBack).coerceAtLeast(0L)
    } else null
    return HaLifecycleCard(
        step = step,
        fills = fills,
        remainingMs = if (measured && !overdue && !ready && step != null) expected!! - elapsed!! else null,
        overdueMs = if (overdue) elapsed!! - expected!! else null,
        usualMs = expected,
        restartedInMs = restartedIn,
        offlineMs = dashboardLostMs.takeIf { step == null },
    )
}

/**
 * The card's secondary line, chosen from the card alone so every state is assertable without a view.
 * Past the usual time it says so in every step: a late reload that still read "Your dashboard is
 * reloading" under a "+4:40" clock looked frozen (production, 2026-10-09).
 */
@StringRes
internal fun haLifecycleDetail(card: HaLifecycleCard): Int = when {
    card.step == null -> if (card.offlineMs != null) R.string.ha_unreachable_retrying else R.string.ha_offline
    card.overdueMs != null -> R.string.ha_past_usual
    card.step == HaRestartStep.RELOADING -> R.string.ha_reloading_detail
    card.remainingMs != null -> R.string.ha_until_back
    else -> R.string.ha_not_measured
}

/** A countdown as minutes and seconds, rounded up so it never reads 0:00 while time remains. */
internal fun haLifecycleClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 999L) / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

/**
 * An exact duration, never rounded to a vaguer unit: whole seconds up to three minutes ("113 seconds"),
 * then minutes and seconds ("6 min 20 s"). "115 seconds" reads as a measurement where "about 2 min"
 * reads as a guess.
 */
internal data class HaExactDuration(val minutes: Long?, val seconds: Long)

internal fun haExactDuration(ms: Long): HaExactDuration {
    val seconds = (ms.coerceAtLeast(0L) + 500L) / 1_000L
    return if (seconds <= 180L) HaExactDuration(null, seconds) else HaExactDuration(seconds / 60L, seconds % 60L)
}

/**
 * The native, dashboard-independent Home Assistant restart notice: the [Notice] in its overlay form.
 *
 * It is a second child of the renderer's root frame, so the dashboard keeps rendering underneath and
 * nothing is destroyed to show it. It is NOT a system overlay: no `SYSTEM_ALERT_WINDOW` owner is added for
 * this.
 *
 * It stays up for as long as the outage imposed from OUTSIDE the panel lasts, because a transient notice
 * would clear while the dashboard was still frozen. It carries the Panel Assistant mark and name, not
 * Home Assistant's, because people took the old card for a Home Assistant feature.
 *
 * The step is carried by words, position and label weight, never by colour alone, so it reads the same to
 * colour-blind viewers.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach], or
 * the card outlives its container.
 */
internal class HaLifecycleBar private constructor(
    private val notice: Notice,
    private val scroll: ScrollView,
    private val hero: TextView,
    private val sub: TextView,
    private val track: NoticeTrack,
    private val footer: TextView,
    /** This card's own renderer state: its activity's, never a replacement's. */
    private val renderer: () -> RendererAdmissionRuntime.Live?,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private val view get() = notice.view
    private var segmentAnimator: ValueAnimator? = null
    private var shownStep: HaRestartStep? = null
    private var dashboardConnected = false

    /**
     * The back-online step retires on read in the state machine, which pushes nothing when it lapses.
     * The view therefore re-reads on its own — but the canonical window runs on `elapsedRealtime`, which
     * keeps counting through deep sleep, while `postDelayed` runs on uptime, which does not. So firing is
     * only a WAKE-UP HINT: the runnable re-reads the canonical snapshot and the canonical clock alone
     * decides. It is always cancelled before rearming.
     */
    private var visible = false
    private fun visibility(next: Boolean) {
        scroll.visibility = if (next) View.VISIBLE else View.GONE
        if (visible != next) {
            visible = next
            onVisibilityChanged(next)
        }
    }
    private val hide = Runnable { update(HaLifecycleRuntime.snapshot()) }

    /** Render current lifecycle intent and this dashboard's connection evidence. */
    fun update(snap: HaLifecycle.Snapshot?) {
        view.removeCallbacks(hide)
        val renderer = renderer()
        val now = SystemClock.elapsedRealtime()
        val state = haLifecycleNoticeState(snap, renderer, now)
        val dashboardLost = haDashboardLostMs(snap, renderer, now)
        if (state == null || state == HaLifecycleState.NORMAL) {
            segmentAnimator?.cancel()
            shownStep = null
            visibility(false)
            // Wake when a grace runs out, since nothing else pushes then.
            listOfNotNull(snap?.offlineGraceRemainingMs, dashboardLost?.let { HaLifecycle.OFFLINE_GRACE_MS - it })
                .filter { it > 0L }.minOrNull()?.let { view.postDelayed(hide, it) }
            return
        }
        dashboardConnected = renderer?.frontendConnected == true
        val card = haLifecycleCard(state, snap, dashboardConnected, dashboardLostMs = dashboardLost)
        render(card, state, snap)
        visibility(true)
        view.postDelayed(hide, TICK_MS)
    }

    private fun render(card: HaLifecycleCard, state: HaLifecycleState, snap: HaLifecycle.Snapshot?) {
        val context = view.context
        val stepWord = when (card.step) {
            HaRestartStep.STOPPING -> context.getString(R.string.ha_step_stopping)
            HaRestartStep.STARTING -> context.getString(R.string.ha_step_starting)
            HaRestartStep.RELOADING -> context.getString(R.string.ha_step_reloading)
            null -> context.getString(if (card.offlineMs != null) R.string.ha_step_retrying else R.string.ha_step_offline)
        }
        notice.setPill(if (card.overdueMs != null) context.getString(R.string.ha_taking_longer) else stepWord)
        val clock = card.remainingMs?.let(::haLifecycleClock) ?: card.overdueMs?.let { "+" + haLifecycleClock(it) }
            ?: card.offlineMs?.let(::haLifecycleClock)
        notice.setHero(hero, clock ?: stepWord, word = clock == null)
        sub.text = context.getString(haLifecycleDetail(card))
        val reason = context.getString(when (snap?.reason ?: HaLifecycleReason.UNKNOWN) {
            HaLifecycleReason.RESTART -> R.string.ha_reason_restart
            HaLifecycleReason.HOST_REBOOT -> R.string.ha_reason_host_reboot
            HaLifecycleReason.CORE_UPDATE -> R.string.ha_reason_core_update
            HaLifecycleReason.UNKNOWN -> R.string.ha_reason_unknown
        })
        footer.text = when {
            card.step == null -> ""
            card.restartedInMs != null ->
                context.getString(R.string.ha_restarted_in, exact(card.restartedInMs))
            card.usualMs != null -> context.getString(R.string.ha_usually, reason, exact(card.usualMs))
            else -> reason
        }
        // INVISIBLE, never GONE: the card wraps its content, so a track or footer leaving would shrink it.
        footer.visibility = if (footer.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        track.view.visibility = if (card.step == null) View.INVISIBLE else View.VISIBLE
        track.show(card.step?.ordinal)
        fill(card, state, snap)
    }

    private fun exact(ms: Long): String {
        val d = haExactDuration(ms)
        val context = view.context
        return if (d.minutes == null) context.getString(R.string.ha_exact_seconds, d.seconds.toInt())
        else context.getString(R.string.ha_exact_minutes_seconds, d.minutes.toInt(), d.seconds.toInt())
    }

    /**
     * The track animates linearly over one second toward where the snapshot clock will be at the next
     * update, so it moves continuously rather than in steps, and never back while the card stays up. A
     * freshly shown card starts at the snapshot's own position.
     */
    private fun fill(card: HaLifecycleCard, state: HaLifecycleState, snap: HaLifecycle.Snapshot?) {
        segmentAnimator?.cancel()
        val fresh = !visible || shownStep == null
        shownStep = card.step
        val segments = track.segments
        val start = segments.mapIndexed { i, bar -> if (fresh) card.fills[i] else maxOf(bar.progress, card.fills[i]) }
        val end = nextFills(card, state, snap).mapIndexed { i, next -> maxOf(next, start[i]) }
        segments.forEachIndexed { i, bar -> bar.progress = start[i] }
        segmentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                segments.forEachIndexed { i, bar -> bar.progress = start[i] + ((end[i] - start[i]) * f).toInt() }
            }
            start()
        }
    }

    /** Where the track will be one tick from now: the same rule, one second later. */
    private fun nextFills(card: HaLifecycleCard, state: HaLifecycleState, snap: HaLifecycle.Snapshot?): List<Int> {
        snap ?: return card.fills
        val ahead = snap.copy(
            elapsedMs = snap.elapsedMs?.plus(TICK_MS),
            backOnlineRemainingMs = (snap.backOnlineRemainingMs - TICK_MS).coerceAtLeast(0L),
        )
        val later = haLifecycleCard(state, ahead, dashboardConnected)
        return if (later.step == card.step) later.fills else card.fills
    }

    fun detach() {
        segmentAnimator?.cancel()
        view.removeCallbacks(hide)
        visibility(false)
        (scroll.parent as? ViewGroup)?.removeView(scroll)
    }

    companion object {
        /** How often the card re-reads the snapshot, and so how far ahead a step animates. */
        private const val TICK_MS = 1_000L

        /** Attach a hidden card to [root], showing the state [renderer] reads. */
        fun attach(
            context: Context,
            root: ViewGroup,
            renderer: () -> RendererAdmissionRuntime.Live?,
            onVisibilityChanged: (Boolean) -> Unit = {},
        ): HaLifecycleBar {
            // Direct Boot: settings unreadable means the dark dashboard, and so the light card.
            val dashboardDark = runCatching { Config(context).dashboardThemeDark }.getOrNull() ?: true
            val notice = Notice(context, dashboardDark, NoticeForm.OVERLAY)
            // The hero's height is fixed at the countdown's, so switching between a countdown and the step in
            // words never resizes the card.
            val hero = notice.hero("").apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = notice.heroNumberHeightPx()
            }
            val sub = notice.line("").apply {
                maxLines = Int.MAX_VALUE
                ellipsize = null
            }
            val track = notice.track(
                listOf(R.string.ha_step_stopping, R.string.ha_step_starting, R.string.ha_step_reloading)
                    .map(context::getString),
                HA_LIFECYCLE_PROGRESS_MAX,
            )
            val footer = notice.footer("")
            notice.setBody(hero, sub, track.view, footer)
            val margin = notice.px(Notice.MARGIN)
            val scroll = ScrollView(context).apply {
                clipToPadding = false
                visibility = View.GONE
                setPadding(0, margin, 0, margin)
                addView(notice.view, FrameLayout.LayoutParams(notice.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            }
            root.addView(
                scroll,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
            )
            return HaLifecycleBar(notice, scroll, hero, sub, track, footer, renderer, onVisibilityChanged)
        }
    }
}
