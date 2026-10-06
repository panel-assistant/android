package io.panelassistant.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.sensors.HaNetworkPath
import io.panelassistant.android.sensors.HaNetworkPathSeverity
import java.util.Locale

/**
 * Decide the chip's text size for a display, in pixels.
 *
 * Pure and Android-free so it is assertable without inflating a view — unit-tested in
 * `HaNetworkChipSizingTest`. A fraction of the shortest edge suits the 480x480 panels; past the
 * baseline panel the fraction would keep growing while the reader does not move closer, so it is
 * capped at the logical dp the baseline yields, scaled by [density] so a density override is honoured.
 */
internal fun haNetworkChipTextSizePx(shortestEdgePx: Float, density: Float): Float {
    val scale = if (density > 0f) density else 1f
    return minOf(shortestEdgePx * HA_NETWORK_CHIP_TEXT_FRACTION, HA_NETWORK_CHIP_MAX_TEXT_DP * scale)
}

private const val HA_NETWORK_CHIP_TEXT_FRACTION = 0.04f
private const val HA_NETWORK_CHIP_BASELINE_EDGE_PX = 480f
private const val HA_NETWORK_CHIP_MAX_TEXT_DP = HA_NETWORK_CHIP_BASELINE_EDGE_PX * HA_NETWORK_CHIP_TEXT_FRACTION

/** One presentation-only decision shared across WebView rebuilds in this process. */
internal class HaNetworkChipVisibility {
    private var dismissed = false

    fun dismiss() { dismissed = true }

    fun update(degraded: Boolean, enabled: Boolean): Boolean {
        if (!degraded) dismissed = false
        return degraded && enabled && !dismissed
    }
}

/**
 * The native, dashboard-independent "HA network slow" chip.
 *
 * A small corner card that is a sibling of the renderer inside the same root frame as
 * [HaLifecycleBar], so the dashboard keeps rendering underneath and nothing is destroyed. It is
 * deliberately NOT the lifecycle bar: that card explains an outage and may stand over the top of the
 * page for as long as one lasts; this states a degraded path that can persist for hours on a working
 * dashboard, so it must cover as little as possible. Only its close button takes a touch; the
 * rest of the card is neither clickable nor focusable and passes taps to the dashboard beneath.
 *
 * Cost: a static view with no animation, composited once per verdict change. Severity is carried
 * by colour (amber for a warning, red for severe) and the wording is one fixed phrase.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach].
 */
internal class HaNetworkChip private constructor(
    private val view: LinearLayout,
    private val label: TextView,
    private val close: ImageButton,
    private val card: GradientDrawable,
    private val icon: WarningGlyph,
    private val dark: Boolean,
) {
    /** Render one atomic snapshot; hidden unless the panel holds a socket AND the path is degraded. */
    fun update(snap: HaNetworkPath.Snapshot?, enabled: Boolean) {
        if (!incidentVisibility.update(snap?.degraded == true, enabled)) {
            view.visibility = View.GONE
            return
        }
        val current = requireNotNull(snap)
        val colours = palette(current.severity, dark)
        card.setColor(colours.surface)
        card.setStroke((BORDER_DP * view.resources.displayMetrics.density).toInt(), colours.border)
        icon.colour = colours.border
        label.setTextColor(colours.label)
        (close.drawable as? CloseGlyph)?.colour = colours.label
        val description = view.context.getString(R.string.ha_network_unreliable)
        label.text = description
        label.contentDescription = description
        view.visibility = View.VISIBLE
    }

    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** A drawn warning triangle: no drawable resource, no dependency on which font owns U+26A0. */
    private class WarningGlyph(private val sizePx: Int) : Drawable() {
        var colour: Int = Color.WHITE
            set(value) { field = value; invalidateSelf() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            fill.color = colour
            val triangle = Path().apply {
                moveTo(b.left + w / 2f, b.top.toFloat())
                lineTo(b.right.toFloat(), b.bottom.toFloat())
                lineTo(b.left.toFloat(), b.bottom.toFloat())
                close()
            }
            canvas.drawPath(triangle, fill)
            mark.color = if (isDark(colour)) Color.WHITE else Color.BLACK
            mark.strokeWidth = w * 0.12f
            val cx = b.left + w / 2f
            canvas.drawLine(cx, b.top + h * 0.35f, cx, b.top + h * 0.68f, mark)
            canvas.drawCircle(cx, b.top + h * 0.84f, w * 0.07f, mark)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
        private fun isDark(c: Int): Boolean =
            (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 128.0
    }

    private class CloseGlyph(private val sizePx: Int) : Drawable() {
        var colour: Int = Color.WHITE
            set(value) { field = value; invalidateSelf() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        override fun draw(canvas: Canvas) {
            val b = bounds
            stroke.color = colour
            stroke.strokeWidth = sizePx * 0.12f
            val inset = sizePx * 0.18f
            canvas.drawLine(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, stroke)
            canvas.drawLine(b.right - inset, b.top + inset, b.left + inset, b.bottom - inset, stroke)
        }
        override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }

    companion object {
        private val incidentVisibility = HaNetworkChipVisibility()
        private data class Palette(val surface: Int, val border: Int, val label: Int)

        /**
         * The lifecycle bar's amber (its STARTING tone) for a warning and its red for severe, so the
         * two native notices speak one colour language. Opaque per theme for the same reason it is:
         * the surface behind is an arbitrary dashboard and a translucent wash has unpredictable contrast.
         */
        private fun palette(severity: HaNetworkPathSeverity, dark: Boolean): Palette = when (severity) {
            HaNetworkPathSeverity.SEVERE -> if (dark)
                Palette(Color.parseColor("#2E1A1D"), Color.parseColor("#D8474D"), Color.parseColor("#FBEBEC"))
            else Palette(Color.parseColor("#FDECEE"), Color.parseColor("#B4292F"), Color.parseColor("#3F1114"))
            else -> if (dark)
                Palette(Color.parseColor("#2A2418"), Color.parseColor("#D2951F"), Color.parseColor("#FAF2E2"))
            else Palette(Color.parseColor("#FDF6E7"), Color.parseColor("#A5741A"), Color.parseColor("#3A2A08"))
        }

        private const val BORDER_DP = 2
        private const val PAD_H_DP = 12
        private const val PAD_V_DP = 8
        private const val MARGIN_DP = 12
        private const val CORNER_DP = 10
        private const val ELEVATION_DP = 6
        /** The glyph is sized to the text so the pair reads as one line at every panel size. */
        private const val ICON_TO_TEXT = 1.05f
        private const val ICON_GAP_DP = 8
        private const val CLOSE_TARGET_DP = 48
        private const val CLOSE_GLYPH_DP = 20

        /**
         * Attach a hidden chip to [root] in the bottom-end corner, clear of the lifecycle bar at the
         * top and of the bezel. Both notices may show at once during a restart on a bad path.
         */
        fun attach(context: Context, root: ViewGroup): HaNetworkChip {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val shortestEdge = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
            val textPx = haNetworkChipTextSizePx(shortestEdge, density)
            val dark = runCatching { Config(context).dashboardThemeDark }.getOrNull() ?: true
            val card = GradientDrawable().apply { cornerRadius = CORNER_DP * density }
            val icon = WarningGlyph((textPx * ICON_TO_TEXT).toInt())
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = card
                elevation = ELEVATION_DP * density
                isClickable = false
                isFocusable = false
            }
            val label = TextView(context).apply {
                setPadding((PAD_H_DP * density).toInt(), (PAD_V_DP * density).toInt(), 0, (PAD_V_DP * density).toInt())
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = (ICON_GAP_DP * density).toInt()
                isClickable = false
                isFocusable = false
                isLongClickable = false
            }
            chip.addView(label)
            val strings = CatalogueLoader.assets(context)
                .strings(AppLocale.resolve(
                    explicit = null, persisted = Config(context).uiLanguage, acceptLanguage = null,
                    deviceLanguageTag = Locale.getDefault().toLanguageTag(), allowPseudo = BuildConfig.DEBUG,
                ))
            val close = ImageButton(context).apply {
                setImageDrawable(CloseGlyph((CLOSE_GLYPH_DP * density).toInt()))
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = strings.get("shell.ha_network.dismiss")
                setOnClickListener { incidentVisibility.dismiss(); chip.visibility = View.GONE }
            }
            chip.addView(close, LinearLayout.LayoutParams(
                (CLOSE_TARGET_DP * density).toInt(), (CLOSE_TARGET_DP * density).toInt(),
            ))
            chip.visibility = View.GONE
            val margin = (MARGIN_DP * density).toInt()
            root.addView(
                chip,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return HaNetworkChip(chip, label, close, card, icon, dark)
        }
    }
}
