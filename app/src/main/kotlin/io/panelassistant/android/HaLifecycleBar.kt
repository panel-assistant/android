package io.panelassistant.android

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleRuntime
import io.panelassistant.android.sensors.HaLifecycleSource
import io.panelassistant.android.sensors.HaLifecycleState
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.haLifecycleDuration

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
    snap?.state == HaLifecycleState.BACK_ONLINE -> snap.state
    else -> null
}

/** The two text sizes the notice renders at, in pixels. */
internal data class HaLifecycleTextSizes(val headlinePx: Float, val detailPx: Float)

/**
 * Decide the notice's text sizes for a display.
 *
 * Pure and Android-free so the geometry is assertable without inflating a view — unit-tested in
 * `HaLifecycleBarSizingTest`.
 *
 * A fraction of the shortest edge suits a small panel and is what the accepted 480x480 rendering uses.
 * Past that the fraction keeps growing with the display while the reader does not move closer, so it is
 * capped at the density-independent logical size already proven readable. The cap is expressed in dp
 * and multiplied by [density] rather than being a pixel constant, so logical-density overrides are
 * respected instead of making the cap smaller in dp.
 *
 * @param shortestEdgePx the shorter of the display's two pixel dimensions
 * @param density `DisplayMetrics.density` — logical pixels per dp, not measured physical DPI
 */
internal fun haLifecycleTextSizes(shortestEdgePx: Float, density: Float): HaLifecycleTextSizes {
    // A non-positive density would silently collapse the cap to zero and hide the text entirely, which
    // is worse than an oversized notice. Fall back to 1:1 rather than trusting it.
    val scale = if (density > 0f) density else 1f
    return HaLifecycleTextSizes(
        headlinePx = minOf(shortestEdgePx * HA_LIFECYCLE_HEADLINE_FRACTION, HA_LIFECYCLE_MAX_HEADLINE_DP * scale),
        detailPx = minOf(shortestEdgePx * HA_LIFECYCLE_DETAIL_FRACTION, HA_LIFECYCLE_MAX_DETAIL_DP * scale),
    )
}

private const val HA_LIFECYCLE_HEADLINE_FRACTION = 0.11f
private const val HA_LIFECYCLE_DETAIL_FRACTION = 0.042f

/**
 * The caps, in dp: exactly what the fraction yields on the smallest supported panel (480px shortest
 * edge at logical density 1.0), which is the rendering already accepted on hardware. Deriving them
 * from the baseline panel rather than picking a number preserves that 480x480 text size while
 * bounding growth on larger logical viewports.
 */
private const val HA_LIFECYCLE_BASELINE_EDGE_PX = 480f
private const val HA_LIFECYCLE_MAX_HEADLINE_DP = HA_LIFECYCLE_BASELINE_EDGE_PX * HA_LIFECYCLE_HEADLINE_FRACTION
private const val HA_LIFECYCLE_MAX_DETAIL_DP = HA_LIFECYCLE_BASELINE_EDGE_PX * HA_LIFECYCLE_DETAIL_FRACTION

/**
 * The native, dashboard-independent Home Assistant outage bar.
 *
 * It is a second child of the renderer's root frame, so the dashboard keeps rendering underneath and
 * nothing is destroyed to show it — unlike the reconnect/auth interstitials, which replace the document.
 * It is NOT a system overlay: no `SYSTEM_ALERT_WINDOW` owner is added for this.
 *
 * Why a bar and not a toast: this explains unresponsiveness imposed from OUTSIDE the panel, so it has to
 * stay visible for as long as that lasts. A transient notice would clear while the dashboard was still
 * frozen, which is the confusion the feature exists to remove. The compact card is deliberate — a
 * full-bleed overlay was tried for the restart announcement and rejected on hardware.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach], or
 * the bar outlives its container — exactly how the earlier attempt failed.
 */
internal class HaLifecycleBar private constructor(
    private val view: LinearLayout,
    private val card: android.graphics.drawable.GradientDrawable,
    private val dark: Boolean,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private val label: TextView = view.getChildAt(1) as TextView
    private val detail: TextView = view.getChildAt(2) as TextView

    /**
     * The back-online notice retires on read in the state machine, which pushes nothing when it lapses.
     * The view therefore times out its own copy — but the canonical window runs on `elapsedRealtime`,
     * which keeps counting through deep sleep, while `postDelayed` runs on uptime, which does not. So
     * firing is only a WAKE-UP HINT: the runnable re-reads the canonical remaining lifetime and either
     * hides or re-arms for exactly what is left, and the canonical clock alone decides. It is always
     * cancelled before rearming, so a rapid second outage cannot be hidden by a previous recovery's hide.
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
        val text = state?.let {
            view.context.getString(when (it) {
                HaLifecycleState.SHUTTING_DOWN -> if (snap?.source == HaLifecycleSource.NATIVE) R.string.ha_shutting_down else R.string.ha_offline
                HaLifecycleState.STARTING -> R.string.ha_starting
                HaLifecycleState.BACK_ONLINE -> R.string.ha_back_online
                HaLifecycleState.CONNECTION_LOST -> R.string.ha_offline
                HaLifecycleState.NORMAL -> return@let null
            })
        }
        if (state == null || text == null) {
            visibility(false)
            if ((snap?.offlineGraceRemainingMs ?: 0L) > 0L) view.postDelayed(hide, snap!!.offlineGraceRemainingMs)
            return
        }
        val colours = palette(state, dark)
        card.setColor(colours.surface)
        card.setStroke((BORDER_DP * view.resources.displayMetrics.density).toInt(), colours.border)
        label.setTextColor(colours.label)
        val overdue = snap?.expectedMs?.let { (snap.elapsedMs ?: 0L) > it } == true &&
            state != HaLifecycleState.BACK_ONLINE
        label.text = if (overdue) view.context.getString(R.string.ha_taking_longer) else text
        val supporting = if (state == HaLifecycleState.BACK_ONLINE) view.context.getString(R.string.controls_returned)
        else {
            val reason = view.context.getString(when (snap?.reason ?: HaLifecycleReason.UNKNOWN) {
                HaLifecycleReason.RESTART -> R.string.ha_reason_restart
                HaLifecycleReason.HOST_REBOOT -> R.string.ha_reason_host_reboot
                HaLifecycleReason.CORE_UPDATE -> R.string.ha_reason_core_update
                HaLifecycleReason.UNKNOWN -> R.string.ha_reason_unknown
            })
            val expected = snap?.expectedMs
            val elapsed = snap?.elapsedMs
            val forecast = if (expected == null || elapsed == null) view.context.getString(R.string.ha_not_measured)
            else {
                val duration = haLifecycleDuration(kotlin.math.abs(expected - elapsed))
                val quantified = view.context.getString(when (duration.unit) {
                    "hours" -> R.string.ha_duration_hours
                    "minutes" -> R.string.ha_duration_minutes
                    else -> R.string.ha_duration_seconds
                }, duration.value)
                view.context.getString(if (overdue) R.string.ha_overdue else R.string.ha_expected_in, quantified)
            }
            "$reason\n$forecast"
        }
        detail.setTextColor(colours.label)
        detail.text = supporting.orEmpty()
        detail.visibility = if (supporting == null) View.GONE else View.VISIBLE
        visibility(true)
        if (state == HaLifecycleState.BACK_ONLINE) {
            // The REMAINING canonical lifetime from the SAME snapshot as the wording — a renderer
            // recreated near expiry finishes the original notice rather than starting a fresh one.
            val remaining = snap?.backOnlineRemainingMs ?: 0L
            if (remaining <= 0L) visibility(false)
            else view.postDelayed(hide, remaining)
        } else view.postDelayed(hide, 1_000L)
    }

    fun detach() {
        view.removeCallbacks(hide)
        visibility(false)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    companion object {
        /**
         * State-coloured, theme-aware and OPAQUE.
         *
         * A near-black card with no border was indistinguishable from the dark dashboard behind it and
         * read as a rendering fault rather than a notice (observed on hardware). Colour fixes that,
         * but three decisions are deliberate:
         *
         * The colour tracks the STATE, not the feature. Red for every state would announce good news in
         * the language of failure; recovery is green.
         *
         * The fill is OPAQUE per theme rather than a translucent wash. Translucency adapts to light and
         * dark for free, but the surface behind is an arbitrary dashboard — camera cards, photographs,
         * bright media — so the resulting text contrast is unpredictable and sometimes unreadable. Two
         * fixed palettes guarantee it.
         *
         * The tint is muted and the SATURATION lives in the border. Home Assistant's guidelines forbid
         * enclosing the logomark in a coloured or confined background, so the mark sits on a barely
         * tinted surface while the stroke carries the signal.
         */
        private data class Palette(val surface: Int, val border: Int, val label: Int)

        private fun palette(state: HaLifecycleState, dark: Boolean): Palette = when (state) {
            HaLifecycleState.BACK_ONLINE -> if (dark)
                Palette(Color.parseColor("#16261C"), Color.parseColor("#3FA45B"), Color.parseColor("#E8F5EC"))
            else Palette(Color.parseColor("#ECF7F0"), Color.parseColor("#2E7D46"), Color.parseColor("#14351F"))
            HaLifecycleState.STARTING -> if (dark)
                Palette(Color.parseColor("#2A2418"), Color.parseColor("#D2951F"), Color.parseColor("#FAF2E2"))
            else Palette(Color.parseColor("#FDF6E7"), Color.parseColor("#A5741A"), Color.parseColor("#3A2A08"))
            // Outage. Red, because from the panel's side every control has just stopped working.
            else -> if (dark)
                Palette(Color.parseColor("#2E1A1D"), Color.parseColor("#D8474D"), Color.parseColor("#FBEBEC"))
            else Palette(Color.parseColor("#FDECEE"), Color.parseColor("#B4292F"), Color.parseColor("#3F1114"))
        }

        private const val BORDER_DP = 3

        /**
         * Sized for a wall panel read from across a room, not a phone held at arm's length. The smallest
         * supported panels are 480x480 at density 160, so 1dp is 1px and the original 24dp mark with 15sp
         * text was genuinely that many pixels on the device (observed on hardware).
         *
         * Exclusion zone: the brand guidelines require clear space of "a quarter the height of the icon",
         * so a 96dp mark needs 24dp. [PAD_DP] is 20dp and [MARGIN_DP] adds 16dp outside it, so the mark
         * has 36dp clear to the bezel and 20dp to the text. Keep PAD_DP + MARGIN_DP >= ICON_DP / 4.
         */
        private const val ICON_DP = 96
        private const val PAD_DP = 20

        /** Keeps the bar clear of the bezel; it must not run to the edges of the screen. */
        private const val MARGIN_DP = 16
        private const val CORNER_DP = 12

        /**
         * The text auto-sizes between these bounds. A fixed 4x size would overflow the 480x480 panels
         * on the longer messages, so the ceiling is what it uses when it fits and the floor is still
         * comfortably larger than the original 15sp.
         */
        /**
         * Sizes are COMPUTED from the display, not auto-sized.
         *
         * Auto-sizing is documented as unreliable against a `wrap_content` height, because the view
         * sizes itself to the text while the text sizes itself to the view. On hardware the headline
         * collapsed to a row of unreadable marks while the supporting line — whose range was narrow
         * enough to survive the circularity — rendered correctly, which is exactly the asymmetry that
         * gave the cause away.
         *
         * A fraction of the shortest screen edge is deterministic and adapts to small panels, but it
         * grows LINEARLY with the display and that is wrong past a point: a wall panel is read from
         * across a room, so it should not keep consuming a larger share of the logical viewport. Measured
         * on hardware — 52.8px (52.8dp) on a 480x480 panel, but 132px (93dp)
         * on a 1920x1200 one, where the card took ~40% of the screen height and dominated the
         * dashboard it annotates. So the fraction is capped at the baseline panel's proven logical dp
         * size; see [haLifecycleTextSizes].
         */
        private const val DETAIL_MAX_LINES = 3

        /** Lifts the card off the dashboard so it reads as laid OVER the page, not part of it. */
        private const val ELEVATION_DP = 8

        /** Caps the headline so a long message truncates rather than pushing the card off the screen. */
        private const val MAX_LINES = 4

        /**
         * Attach a hidden bar to [root]. The icon is best-effort: when it is unavailable the bar is
         * text-only and still names Home Assistant, so the message never depends on artwork resolving.
         */
        fun attach(context: Context, root: ViewGroup, onVisibilityChanged: (Boolean) -> Unit = {}): HaLifecycleBar {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val pad = (PAD_DP * density).toInt()
            val iconSize = (ICON_DP * density).toInt()
            val shortestEdge = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
            val sizes = haLifecycleTextSizes(shortestEdge, density)
            val headlinePx = sizes.headlinePx
            val detailPx = sizes.detailPx
            // VERTICAL and centred: the mark sits on its own line with the wording centred beneath it.
            // A horizontal row spent a fifth of a 480px panel on the mark and left the text a narrow
            // column beside it; stacking gives the wording the full width and reads as a notice.
            val dark = runCatching { Config(context).dashboardThemeDark }.getOrNull() ?: true
            val card = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                background = card
                elevation = ELEVATION_DP * density
                visibility = View.GONE
            }
            row.addView(
                ImageView(context).apply {
                    HaBrandIcon.drawable(context)?.let(::setImageDrawable)
                    // Attributive, not informative: the wording carries the meaning on its own.
                    contentDescription = context.getString(R.string.home_assistant)
                },
                LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = pad
                },
            )
            row.addView(
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    // Auto-size so the ceiling is used whenever it fits and a longer message shrinks
                    // rather than overflowing. Stacked, the label has the full card width to work with.
                    maxLines = MAX_LINES
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, headlinePx)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            row.addView(
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    alpha = 0.85f
                    maxLines = DETAIL_MAX_LINES
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, detailPx)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad / 2 },
            )
            val margin = (MARGIN_DP * density).toInt()
            root.addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return HaLifecycleBar(row, card, dark, onVisibilityChanged)
        }
    }
}
