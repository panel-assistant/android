package io.panelassistant.android

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleRuntime
import io.panelassistant.android.sensors.HaLifecycleSource
import io.panelassistant.android.sensors.HaLifecycleState

/** Select the notice shown over the dashboard from the current owners' observations. */
internal fun haLifecycleNoticeState(
    snap: HaLifecycle.Snapshot?,
    renderer: RendererAdmissionRuntime.Live?,
): HaLifecycleState? = when {
    snap?.state == HaLifecycleState.SHUTTING_DOWN || snap?.state == HaLifecycleState.STARTING -> snap.state
    snap?.state == HaLifecycleState.CONNECTION_LOST && snap.offlineGraceRemainingMs > 0L -> null
    renderer?.record?.state == RendererAdmissionState.ADMITTED &&
        renderer.record.admittedOnCachedVersion && !renderer.frontendConnected -> HaLifecycleState.CONNECTION_LOST
    snap?.state == HaLifecycleState.CONNECTION_LOST && snap.offlineGraceRemainingMs <= 0L -> snap.state
    // The last step lasts while the dashboard reloads and ends the moment it reconnects: a connected
    // dashboard is usable, and a card still covering it would be Panel Assistant getting in the way.
    snap?.state == HaLifecycleState.BACK_ONLINE && renderer?.frontendConnected != true -> snap.state
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
 * @property remainingMs time left to the measured usual time; null when not measured, overdue or reloading
 * @property overdueMs how far past the usual time; null unless overdue
 * @property usualMs the measured usual time
 * @property restartedInMs while reloading, how long this restart took
 */
internal data class HaLifecycleCard(
    val step: HaRestartStep?,
    val fills: List<Int>,
    val remainingMs: Long?,
    val overdueMs: Long?,
    val usualMs: Long?,
    val restartedInMs: Long?,
)

internal const val HA_LIFECYCLE_PROGRESS_MAX = 10_000

/**
 * Decide the card for [state].
 *
 * Only the whole restart is measured, not each step, so the measured progress (elapsed ÷ usual time)
 * is shared out: Stopping fills over the first half of the usual time, and Starting fills from wherever
 * the restart was when this card first saw it start ([startingFrom]) to the usual time, so it never
 * sits empty waiting for a halfway mark. A step that has finished is full whatever the clock says. Past
 * the usual time both sit full, because progress is capped there, and never run backwards. Reloading
 * fills over the back-online window.
 * With no measurement there is no countdown and no partial fill: the track still shows which step it is.
 *
 * @param startingFrom the overall progress, out of [HA_LIFECYCLE_PROGRESS_MAX], when Starting was first shown
 */
internal fun haLifecycleCard(
    state: HaLifecycleState,
    snap: HaLifecycle.Snapshot?,
    startingFrom: Int? = null,
    backOnlineWindowMs: Long = HaLifecycle.DEFAULT_BACK_ONLINE_WINDOW_MS,
): HaLifecycleCard {
    val step = when (state) {
        HaLifecycleState.SHUTTING_DOWN ->
            if (snap?.source == HaLifecycleSource.NATIVE) HaRestartStep.STOPPING else null
        HaLifecycleState.STARTING -> HaRestartStep.STARTING
        HaLifecycleState.BACK_ONLINE -> HaRestartStep.RELOADING
        HaLifecycleState.CONNECTION_LOST, HaLifecycleState.NORMAL -> null
    }
    val expected = snap?.expectedMs?.takeIf { it > 0L }
    val elapsed = snap?.elapsedMs?.coerceAtLeast(0L)
    val measured = expected != null && elapsed != null
    val max = HA_LIFECYCLE_PROGRESS_MAX
    val progress = if (measured) (minOf(elapsed!!, expected!!) * max / expected).toInt() else 0
    val overdue = measured && step != HaRestartStep.RELOADING && elapsed!! > expected!!
    val fills = when (step) {
        null -> listOf(0, 0, 0)
        HaRestartStep.STOPPING -> listOf(minOf(max, progress * 2), 0, 0)
        HaRestartStep.STARTING -> {
            val from = (startingFrom ?: progress).coerceIn(0, max - 1)
            val fill = when {
                !measured -> 0
                else -> ((progress - from).coerceAtLeast(0).toLong() * max / (max - from)).toInt()
            }
            listOf(max, fill, 0)
        }
        HaRestartStep.RELOADING -> {
            val remaining = (snap?.backOnlineRemainingMs ?: 0L).coerceIn(0L, backOnlineWindowMs)
            listOf(max, max, ((backOnlineWindowMs - remaining) * max / backOnlineWindowMs).toInt())
        }
    }
    val restartedIn = if (step == HaRestartStep.RELOADING && elapsed != null) {
        val sinceBack = backOnlineWindowMs - (snap?.backOnlineRemainingMs ?: 0L).coerceIn(0L, backOnlineWindowMs)
        (elapsed - sinceBack).coerceAtLeast(0L)
    } else null
    return HaLifecycleCard(
        step = step,
        fills = fills,
        remainingMs = if (measured && !overdue && step != null && step != HaRestartStep.RELOADING) expected!! - elapsed!! else null,
        overdueMs = if (overdue) elapsed!! - expected!! else null,
        usualMs = expected,
        restartedInMs = restartedIn,
    )
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
 * How much to scale the card's baseline sizes, which are its pixel sizes on the smallest supported
 * panel (480px shortest edge at logical density 1.0).
 *
 * The card grows with a small display, but past the baseline it is capped at the display's logical
 * density: a wall panel is read from across a room, so the card must not keep taking a larger share of
 * a bigger screen (measured on a 1920x1200 panel, where an uncapped notice took ~40% of the height).
 * Logical-density overrides are respected because the cap is the density itself.
 *
 * @param shortestEdgePx the shorter of the display's two pixel dimensions
 * @param density `DisplayMetrics.density` — logical pixels per dp, not measured physical DPI
 */
internal fun haLifecycleScale(shortestEdgePx: Float, density: Float): Float {
    // A non-positive density would collapse the card to nothing, which is worse than an oversized one.
    val cap = if (density > 0f) density else 1f
    return minOf(shortestEdgePx / HA_LIFECYCLE_BASELINE_EDGE_PX, cap)
}

private const val HA_LIFECYCLE_BASELINE_EDGE_PX = 480f

/**
 * The native, dashboard-independent Home Assistant restart notice.
 *
 * It is a second child of the renderer's root frame, so the dashboard keeps rendering underneath and
 * nothing is destroyed to show it — unlike the reconnect/auth interstitials, which replace the document.
 * It is NOT a system overlay: no `SYSTEM_ALERT_WINDOW` owner is added for this.
 *
 * It stays up for as long as the outage imposed from OUTSIDE the panel lasts, because a transient
 * notice would clear while the dashboard was still frozen. It carries the Panel Assistant mark and name,
 * not Home Assistant's, because people took the old card for a Home Assistant feature.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach], or
 * the card outlives its container.
 */
internal class HaLifecycleBar private constructor(
    private val parts: Parts,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private class Parts(
        val card: LinearLayout,
        val pillText: TextView,
        val hero: TextView,
        val sub: TextView,
        val track: View,
        val segments: List<ProgressBar>,
        val labels: List<TextView>,
        val footer: TextView,
        val palette: Palette,
        val heroClockPx: Float,
    )

    private val view get() = parts.card
    private var segmentAnimator: ObjectAnimator? = null
    private var shownStep: HaRestartStep? = null
    private var startingFrom: Int? = null

    /**
     * The back-online step retires on read in the state machine, which pushes nothing when it lapses.
     * The view therefore re-reads on its own — but the canonical window runs on `elapsedRealtime`, which
     * keeps counting through deep sleep, while `postDelayed` runs on uptime, which does not. So firing is
     * only a WAKE-UP HINT: the runnable re-reads the canonical snapshot and the canonical clock alone
     * decides. It is always cancelled before rearming.
     */
    private var visible = false
    private fun visibility(next: Boolean) {
        view.visibility = if (next) View.VISIBLE else View.GONE
        if (visible != next) {
            visible = next
            onVisibilityChanged(next)
        }
    }
    private val hide = Runnable { update(HaLifecycleRuntime.snapshot(), RendererAdmissionRuntime.current()) }

    /** Render current lifecycle intent and this dashboard's connection evidence. */
    fun update(snap: HaLifecycle.Snapshot?, renderer: RendererAdmissionRuntime.Live?) {
        view.removeCallbacks(hide)
        val state = haLifecycleNoticeState(snap, renderer)
        if (state == null || state == HaLifecycleState.NORMAL) {
            segmentAnimator?.cancel()
            shownStep = null
            visibility(false)
            if ((snap?.offlineGraceRemainingMs ?: 0L) > 0L) view.postDelayed(hide, snap!!.offlineGraceRemainingMs)
            return
        }
        if (haLifecycleCard(state, snap).step != HaRestartStep.STARTING) startingFrom = null
        else if (startingFrom == null) startingFrom = progressOf(snap)
        val card = haLifecycleCard(state, snap, startingFrom)
        render(card, state, snap)
        visibility(true)
        view.postDelayed(hide, TICK_MS)
    }

    private fun progressOf(snap: HaLifecycle.Snapshot?): Int? {
        val expected = snap?.expectedMs?.takeIf { it > 0L } ?: return null
        val elapsed = snap.elapsedMs?.coerceAtLeast(0L) ?: return null
        return (minOf(elapsed, expected) * HA_LIFECYCLE_PROGRESS_MAX / expected).toInt()
    }

    private fun render(card: HaLifecycleCard, state: HaLifecycleState, snap: HaLifecycle.Snapshot?) {
        val context = view.context
        val stepWord = when (card.step) {
            HaRestartStep.STOPPING -> context.getString(R.string.ha_step_stopping)
            HaRestartStep.STARTING -> context.getString(R.string.ha_step_starting)
            HaRestartStep.RELOADING -> context.getString(R.string.ha_step_reloading)
            null -> context.getString(R.string.ha_step_offline)
        }
        parts.pillText.text = if (card.overdueMs != null) context.getString(R.string.ha_taking_longer) else stepWord
        val clock = card.remainingMs?.let(::haLifecycleClock) ?: card.overdueMs?.let { "+" + haLifecycleClock(it) }
        setHero(clock ?: stepWord, clock != null)
        parts.sub.text = context.getString(when {
            card.step == null -> R.string.ha_offline
            card.step == HaRestartStep.RELOADING -> R.string.ha_reloading_detail
            card.overdueMs != null -> R.string.ha_past_usual
            card.remainingMs != null -> R.string.ha_until_back
            else -> R.string.ha_not_measured
        })
        val reason = context.getString(when (snap?.reason ?: HaLifecycleReason.UNKNOWN) {
            HaLifecycleReason.RESTART -> R.string.ha_reason_restart
            HaLifecycleReason.HOST_REBOOT -> R.string.ha_reason_host_reboot
            HaLifecycleReason.CORE_UPDATE -> R.string.ha_reason_core_update
            HaLifecycleReason.UNKNOWN -> R.string.ha_reason_unknown
        })
        parts.footer.text = when {
            card.step == null -> ""
            card.step == HaRestartStep.RELOADING && card.restartedInMs != null ->
                context.getString(R.string.ha_restarted_in, exact(card.restartedInMs))
            card.usualMs != null -> context.getString(R.string.ha_usually, reason, exact(card.usualMs))
            else -> reason
        }
        // INVISIBLE, never GONE: the card wraps its content, so a track or footer leaving would shrink it.
        parts.footer.visibility = if (parts.footer.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        parts.track.visibility = if (card.step == null) View.INVISIBLE else View.VISIBLE
        val current = card.step?.ordinal
        parts.labels.forEachIndexed { i, label ->
            val active = i == current
            label.setTextColor(if (active) parts.palette.text else parts.palette.muted)
            label.typeface = if (active) MEDIUM else Typeface.DEFAULT
        }
        fill(card, state, snap)
    }

    private fun setHero(text: String, isClock: Boolean) {
        parts.hero.text = text
        parts.hero.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (isClock) parts.heroClockPx else parts.heroClockPx * HERO_WORD / HERO_CLOCK)
    }

    private fun exact(ms: Long): String {
        val d = haExactDuration(ms)
        val context = view.context
        return if (d.minutes == null) context.getString(R.string.ha_exact_seconds, d.seconds.toInt())
        else context.getString(R.string.ha_exact_minutes_seconds, d.minutes.toInt(), d.seconds.toInt())
    }

    /**
     * Finished steps sit full and later ones empty; the current step animates linearly over one second
     * toward where the snapshot clock will be at the next update, so it moves continuously rather than in
     * steps, and never back within a step. A freshly shown step jumps straight to its value.
     */
    private fun fill(card: HaLifecycleCard, state: HaLifecycleState, snap: HaLifecycle.Snapshot?) {
        segmentAnimator?.cancel()
        val freshStep = !visible || shownStep != card.step
        shownStep = card.step
        parts.segments.forEachIndexed { i, bar -> if (i != card.step?.ordinal || freshStep) bar.progress = card.fills[i] }
        val i = card.step?.ordinal ?: return
        val bar = parts.segments[i]
        val next = maxOf(bar.progress, nextFill(card, i, state, snap))
        segmentAnimator = ObjectAnimator.ofInt(bar, "progress", bar.progress, next).apply {
            duration = TICK_MS
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Where the current step's fill will be one tick from now: the same rule, one second later. */
    private fun nextFill(card: HaLifecycleCard, i: Int, state: HaLifecycleState, snap: HaLifecycle.Snapshot?): Int {
        snap ?: return card.fills[i]
        val ahead = snap.copy(
            elapsedMs = snap.elapsedMs?.plus(TICK_MS),
            backOnlineRemainingMs = (snap.backOnlineRemainingMs - TICK_MS).coerceAtLeast(0L),
        )
        val later = haLifecycleCard(state, ahead, startingFrom)
        return if (later.step == card.step) later.fills[i] else card.fills[i]
    }

    fun detach() {
        segmentAnimator?.cancel()
        view.removeCallbacks(hide)
        visibility(false)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /**
     * One palette per dashboard theme, not per state: the step is carried by words, position and label
     * weight, never by colour alone, so it reads the same to colour-blind viewers. Every text role keeps
     * WCAG AA 4.5:1 or better over a pure white, grey or black dashboard at [CARD_ALPHA] (worst 5.4:1:
     * secondary labels, dark theme over white); the accent marks only the dot and the track, which need
     * 3:1 and get 5.1:1 or better. Recheck those figures before changing a colour or the opacity.
     */
    private class Palette(
        val surface: Int,
        val text: Int,
        val secondary: Int,
        val muted: Int,
        val accent: Int,
    ) {
        val line: Int get() = withAlpha(text, 0x1F)
    }

    companion object {
        private val DARK = Palette(
            surface = Color.parseColor("#16181C"), text = Color.parseColor("#F3F5F7"),
            secondary = Color.parseColor("#C3CAD1"), muted = Color.parseColor("#A9B2BC"),
            accent = Color.parseColor("#4FC3F7"),
        )
        private val LIGHT = Palette(
            surface = Color.parseColor("#F7F8FA"), text = Color.parseColor("#15181C"),
            secondary = Color.parseColor("#3D4650"), muted = Color.parseColor("#46505B"),
            accent = Color.parseColor("#01579B"),
        )

        /** 86% opaque, so the dashboard shows faintly through; see [Palette] for the contrast it keeps. */
        private const val CARD_ALPHA = 0xDB

        private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val LIGHT_FACE: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

        /** How often the card re-reads the snapshot, and so how far ahead a step animates. */
        private const val TICK_MS = 1_000L

        // Baseline sizes: pixels on a 480x480 panel at density 1.0, scaled by [haLifecycleScale].
        private const val MARGIN = 16f
        private const val PAD_H = 28f
        private const val PAD_V = 24f
        private const val GAP = 18f
        private const val CORNER = 24f
        private const val MARK = 40f
        private const val BRAND = 17f
        private const val PILL = 16f
        private const val DOT = 9f
        private const val HERO_CLOCK = 96f
        private const val HERO_WORD = 56f
        private const val SUB = 20f
        private const val SEGMENT = 12f
        private const val SEGMENT_GAP = 6f
        private const val LABEL = 16f
        private const val FOOTER = 16f

        /** The card never takes a landscape panel's full width; it sits centred at this baseline width. */
        private const val MAX_WIDTH = 600f

        private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha shl 24)

        /** Attach a hidden card to [root]. */
        fun attach(context: Context, root: ViewGroup, onVisibilityChanged: (Boolean) -> Unit = {}): HaLifecycleBar {
            val metrics = context.resources.displayMetrics
            val s = haLifecycleScale(minOf(metrics.widthPixels, metrics.heightPixels).toFloat(), metrics.density)
            fun px(base: Float) = (base * s).toInt()
            val dark = runCatching { Config(context).dashboardThemeDark }.getOrNull() ?: true
            val palette = if (dark) DARK else LIGHT

            fun text(size: Float, colour: Int, face: Typeface = Typeface.DEFAULT) = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
                setTextColor(colour)
                typeface = face
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(PAD_H), px(PAD_V), px(PAD_H), px(PAD_V))
                background = GradientDrawable().apply {
                    cornerRadius = CORNER * s
                    setColor(withAlpha(palette.surface, CARD_ALPHA))
                    setStroke(maxOf(1, (1f * s).toInt()), palette.line)
                }
                visibility = View.GONE
            }

            // Header: the Panel Assistant mark and name, and the step pill.
            val pillText = text(PILL, palette.text, MEDIUM).apply { maxLines = 1 }
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14f), px(7f), px(14f), px(7f))
                background = GradientDrawable().apply {
                    cornerRadius = 999f * s
                    setColor(withAlpha(palette.accent, 0x24))
                }
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent) }
                }, LinearLayout.LayoutParams(px(DOT), px(DOT)).apply { marginEnd = px(8f) })
                addView(pillText)
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_pa_mark)
                    // Attributive: the name beside it says whose notice this is.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(px(MARK), px(MARK)).apply { marginEnd = px(12f) })
                addView(text(BRAND, palette.muted, MEDIUM).apply {
                    text = context.getString(R.string.panel_assistant)
                    maxLines = 1
                    letterSpacing = 0.02f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill)
            }
            card.addView(header)

            // The hero: a countdown when the restart is measured, else the step in words. Its height is
            // fixed at the countdown's, so switching between them never resizes the card.
            val hero = text(HERO_CLOCK, palette.text, LIGHT_FACE).apply {
                maxLines = 1
                fontFeatureSettings = "tnum"
                letterSpacing = -0.01f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = Paint().apply { textSize = HERO_CLOCK * s; typeface = LIGHT_FACE }
                    .fontMetricsInt.let { it.bottom - it.top }
            }
            card.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(GAP) })
            val sub = text(SUB, palette.secondary)
            card.addView(sub)

            // The track: one segment per step in a single accent colour, with its name beneath.
            val corner = SEGMENT * s / 2
            val segments = List(3) {
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = false
                    max = HA_LIFECYCLE_PROGRESS_MAX
                    progressDrawable = LayerDrawable(arrayOf(
                        GradientDrawable().apply { cornerRadius = corner; setColor(palette.line) },
                        ClipDrawable(GradientDrawable().apply { cornerRadius = corner; setColor(palette.accent) },
                            Gravity.START, ClipDrawable.HORIZONTAL),
                    )).apply {
                        setId(0, android.R.id.background)
                        setId(1, android.R.id.progress)
                    }
                }
            }
            val labels = listOf(R.string.ha_step_stopping, R.string.ha_step_starting, R.string.ha_step_reloading).map {
                text(LABEL, palette.muted).apply { setText(it); maxLines = 1 }
            }
            fun row(children: List<View>, height: Int) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                children.forEachIndexed { i, child ->
                    addView(child, LinearLayout.LayoutParams(0, height, 1f).apply { if (i > 0) marginStart = px(SEGMENT_GAP) })
                }
            }
            val track = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(row(segments, px(SEGMENT)))
                addView(row(labels, ViewGroup.LayoutParams.WRAP_CONTENT),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = px(10f) })
            }
            card.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(GAP) })

            val footer = text(FOOTER, palette.muted)
            card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(GAP) })

            val margin = px(MARGIN)
            root.addView(
                card,
                FrameLayout.LayoutParams(
                    minOf(metrics.widthPixels - 2 * margin, px(MAX_WIDTH)),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return HaLifecycleBar(Parts(card, pillText, hero, sub, track, segments, labels, footer, palette, HERO_CLOCK * s), onVisibilityChanged)
        }
    }
}
